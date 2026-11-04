package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ImportantDate
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * اختبارات اشتقاق المناسبات وقتَ القراءة فوق مستودع وهمي (D1): ساعة ومنطقة محقونة
 * ثابتتان، أطراف النافذة، استبعاد CUSTOM، إهمال سنة المصدر، حساسية المنطقة الزمنية
 * المحقونة، واستقلال الاشتقاق عن تكرار أرحام (OCCASIONS وغيرها تصلك بالتواريخ نفسها).
 */
class ObserveUpcomingOccasionsUseCaseTest {

    private class FakeArhamRepository : ArhamRepository {
        var contacts: List<Contact> = emptyList()
        val upserts = mutableListOf<Pair<Long, ArhamInfo>>()
        val calls = mutableListOf<Pair<Long, Long>>()

        override fun observeArhamContacts(): Flow<List<Contact>> = MutableStateFlow(contacts)

        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
            upserts += contactId to info
        }

        override suspend fun recordContactCall(contactId: Long, timestampMillis: Long) {
            calls += contactId to timestampMillis
        }
    }

    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.parse("2026-02-27T10:00:00Z") // اليوم (UTC) = 2026-02-27
    }

    private fun contact(
        id: Long,
        name: String,
        frequency: ArhamFrequency = ArhamFrequency.WEEKLY,
        dates: List<ImportantDate> = emptyList(),
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = name),
        dates = dates,
        arham = ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = frequency),
    )

    private fun useCase(
        repository: FakeArhamRepository,
        timeZone: TimeZone = TimeZone.UTC,
    ) = ObserveUpcomingOccasionsUseCase(
        repository = repository,
        clock = fixedClock,
        timeZone = timeZone,
    )

    @Test
    fun birthdayToday_isDerivedWithZeroDays() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(contact(1, "أمين", dates = listOf(ImportantDate("BIRTHDAY", month = 2, day = 27))))
        }
        val occasions = useCase(repo)().first()
        assertEquals(1, occasions.size)
        assertEquals(0, occasions[0].daysUntil)
        assertEquals("BIRTHDAY", occasions[0].type)
        assertEquals(1L, occasions[0].contactId)
    }

    @Test
    fun sourceYear_isIgnored_annualRecurrenceEitherWay() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                // سنة مصدر ماضية (ميلاد 1990) — التكرار السنوي نفسه بلا سنة.
                contact(1, "ميلاد 1990", dates = listOf(ImportantDate("BIRTHDAY", year = 1990, month = 3, day = 6))),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(1, occasions.size)
        assertEquals(7, occasions[0].daysUntil) // 2026-03-06 − 2026-02-27
        assertEquals(1990, occasions[0].year)   // محفوظة للعرض، لا للاشتقاق
        assertEquals(2026, occasions[0].nextOccurrence.year)
    }

    @Test
    fun injectedTimeZone_drivesToday_notHardcodedUtc() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(contact(1, "رياض", dates = listOf(ImportantDate("BIRTHDAY", month = 2, day = 28))))
        }
        // 22:00Z = 2026-02-27 في UTC لكن 2026-02-28 01:00 في الرياض (+03) —
        // المناسبة (28 فبراير) «اليوم» بالرياض و«غداً» بـ UTC: المنطقتان تثبتان أن
        // «اليوم» مشتق من المنطقة المحقونة لا من UTC مضمومة.
        val eveningClock = object : AppClock {
            override fun now(): Instant = Instant.parse("2026-02-27T22:00:00Z")
        }
        val riyadh = ObserveUpcomingOccasionsUseCase(repo, eveningClock, TimeZone.of("Asia/Riyadh"))().first()
        assertEquals(1, riyadh.size)
        assertEquals(0, riyadh[0].daysUntil)

        val utc = ObserveUpcomingOccasionsUseCase(repo, eveningClock, TimeZone.UTC)().first()
        assertEquals(1, utc.size)
        assertEquals(1, utc[0].daysUntil)
    }

    @Test
    fun windowBoundaries_zeroAndSevenIncluded_eightExcluded() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "د0", dates = listOf(ImportantDate("BIRTHDAY", month = 2, day = 27))),  // 0 يوم
                contact(2, "د7", dates = listOf(ImportantDate("BIRTHDAY", month = 3, day = 6))),   // 7 أيام
                contact(3, "د8", dates = listOf(ImportantDate("BIRTHDAY", month = 3, day = 7))),   // 8 أيام
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(listOf(1L, 2L), occasions.map { it.contactId })
    }

    @Test
    fun pastDateThisYear_rollsToNextYear_andIsOutsideWindow() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                // 20 فبراير مضى؛ التكرار القادم 2027 — خارج نافذة 7 أيام.
                contact(1, "ماضٍ", dates = listOf(ImportantDate("BIRTHDAY", month = 2, day = 20))),
            )
        }
        assertTrue(useCase(repo)().first().isEmpty())
    }

    @Test
    fun customTypeDates_areExcluded_perD1() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(
                    1,
                    "مخصص",
                    dates = listOf(
                        ImportantDate("CUSTOM", month = 2, day = 27, label = "ذكرى"),
                        ImportantDate("ANNIVERSARY", month = 3, day = 1),
                    ),
                ),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(1, occasions.size)
        assertEquals("ANNIVERSARY", occasions[0].type)
    }

    @Test
    fun occasionsFrequency_contactsAreStillDerived() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(
                    1,
                    "عيد",
                    frequency = ArhamFrequency.OCCASIONS,
                    dates = listOf(ImportantDate("BIRTHDAY", month = 3, day = 2)),
                ),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(1, occasions.size)
        assertEquals(3, occasions[0].daysUntil)
    }

    @Test
    fun multipleDatesOnOneContact_areAllDerived() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(
                    1,
                    "متعدد",
                    dates = listOf(
                        ImportantDate("BIRTHDAY", month = 3, day = 6),
                        ImportantDate("ANNIVERSARY", month = 2, day = 27),
                    ),
                ),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(2, occasions.size)
        assertEquals(listOf(0, 7), occasions.map { it.daysUntil }) // مرتَّبة بالأقرب
    }

    @Test
    fun ties_breakDeterministically_byNameThenId() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "بسمة", dates = listOf(ImportantDate("BIRTHDAY", month = 3, day = 1))),
                contact(2, "أمين", dates = listOf(ImportantDate("BIRTHDAY", month = 3, day = 1))),
                contact(3, "أمين", dates = listOf(ImportantDate("ANNIVERSARY", month = 3, day = 1))),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(listOf(2L, 3L, 1L), occasions.map { it.contactId })
    }

    @Test
    fun noDates_orEmptyContacts_yieldEmptyList() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(contact(1, "بلا تواريخ"), contact(2, "أيضاً بلا"))
        }
        assertTrue(useCase(repo)().first().isEmpty())
        repo.contacts = emptyList()
        assertTrue(useCase(repo)().first().isEmpty())
    }

    @Test
    fun invalidDeviceDate_isSkippedWithoutCrashing() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(
                    1,
                    "بيانات فاسدة",
                    dates = listOf(
                        ImportantDate("BIRTHDAY", month = 13, day = 5),  // شهر غير صالح
                        ImportantDate("BIRTHDAY", month = 2, day = 30),  // غير موجود
                        ImportantDate("BIRTHDAY", month = 3, day = 1),   // صالح
                    ),
                ),
            )
        }
        val occasions = useCase(repo)().first()
        assertEquals(1, occasions.size)
        assertEquals(2, occasions[0].daysUntil)
    }
}
