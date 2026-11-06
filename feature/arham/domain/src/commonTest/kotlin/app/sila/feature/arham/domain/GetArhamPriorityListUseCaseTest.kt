package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * اختبارات استبعاد OCCASIONS من قائمة الأولويات (AC-2 — خطة §P-T8-05): جهات «ب المناسبات»
 * لا تدخل الترتيب إطلاقاً — تظهر عبر بطاقة/تنبيه المناسبة فقط — مع إعادة ترقيم متسقة 01….
 */
class GetArhamPriorityListUseCaseTest {

    private class FakeArhamRepository : ArhamRepository {
        var contacts: List<Contact> = emptyList()
        val upserts = mutableListOf<Pair<Long, ArhamInfo>>()

        override fun observeArhamContacts(): Flow<List<Contact>> = MutableStateFlow(contacts)

        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
            upserts += contactId to info
        }
    }

    private val baseMillis = Instant.parse("2026-02-27T10:00:00Z").toEpochMilliseconds()
    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.parse("2026-02-27T10:00:00Z")
    }

    private fun contact(
        id: Long,
        name: String,
        frequency: ArhamFrequency,
        lastContactedDaysAgo: Int?,
        pausedInDays: Int? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = name),
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = frequency,
            lastContactedAt = lastContactedDaysAgo?.let { baseMillis - it * 86_400_000L },
            pausedUntil = pausedInDays?.let { baseMillis + it * 86_400_000L },
        ),
    )

    @Test
    fun occasionsFrequency_isExcludedFromRanking() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "أسبوعي متأخر", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 14),  // أحمر
                contact(2, "مناسبات", ArhamFrequency.OCCASIONS, lastContactedDaysAgo = null),  // يُستبعد
                contact(3, "أسبوعي قريب", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 3),    // أخضر
            )
        }
        val items = GetArhamPriorityListUseCase(repo, fixedClock)().first()
        assertEquals(listOf(1L, 3L), items.map { it.contactId }) // OCCASIONS خرج من القائمة
        assertEquals(listOf(1, 2), items.map { it.rank })        // الترقيم 01… مُعاد بلا فراغات
    }

    @Test
    fun orderingStaysDescendingByUrgency_afterExclusion() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "أحمر", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 14),
                contact(2, "أصفر", ArhamFrequency.MONTHLY, lastContactedDaysAgo = 25), // 25/30 ≈ 0.83
                contact(3, "أخضر", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 1),
                contact(4, "مناسبات", ArhamFrequency.OCCASIONS, lastContactedDaysAgo = 100),
            )
        }
        val items = GetArhamPriorityListUseCase(repo, fixedClock)().first()
        assertEquals(listOf(1L, 2L, 3L), items.map { it.contactId })
        assertTrue(items.zipWithNext().all { (a, b) -> a.urgency >= b.urgency })
        assertEquals(ArhamStatus.OVERDUE, items[0].status)
        assertEquals(ArhamStatus.OK, items[2].status)
    }

    @Test
    fun allOccasionsYield_emptyList() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "مناسبات", ArhamFrequency.OCCASIONS, lastContactedDaysAgo = null),
                contact(2, "مناسبات أخرى", ArhamFrequency.OCCASIONS, lastContactedDaysAgo = 5),
            )
        }
        val items = GetArhamPriorityListUseCase(repo, fixedClock)().first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun neverContacted_weeklyStillRanks_onTop() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "لم يتصل بعد", ArhamFrequency.WEEKLY, lastContactedDaysAgo = null), // MAX_VALUE
                contact(2, "مناسبات", ArhamFrequency.OCCASIONS, lastContactedDaysAgo = null),
            )
        }
        val items: List<ArhamItem> = GetArhamPriorityListUseCase(repo, fixedClock)().first()
        assertEquals(listOf(1L), items.map { it.contactId })
    }

    @Test
    fun pausedContact_hiddenFromList_andRenumberedWithoutGaps() = runTest {
        // AC-2 «موقوف بعدد» — مؤجل من T8-05 إلى T8-07: الموقوف حالياً يخرج من القائمة
        // حصراً، والترقيم يعاد بلا فراغات؛ عدده يظهر في اللوحة (pausedCount).
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "أحمر", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 14),
                contact(2, "موقوف", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 3, pausedInDays = 10),
                contact(3, "أخضر", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 1),
                contact(4, "انتهى توقيفه", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 5, pausedInDays = -1),
            )
        }
        val items = GetArhamPriorityListUseCase(repo, fixedClock)().first()
        assertEquals(listOf(1L, 4L, 3L), items.map { it.contactId }) // الموقوف 2 مخفي
        assertEquals(listOf(1, 2, 3), items.map { it.rank })         // بلا فراغ ترقيم
    }

    @Test
    fun allPausedContacts_yieldEmptyList() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "موقوف أول", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 2, pausedInDays = 7),
                contact(2, "موقوف ثان", ArhamFrequency.MONTHLY, lastContactedDaysAgo = 2, pausedInDays = 7),
            )
        }
        assertTrue(GetArhamPriorityListUseCase(repo, fixedClock)().first().isEmpty())
    }
}
