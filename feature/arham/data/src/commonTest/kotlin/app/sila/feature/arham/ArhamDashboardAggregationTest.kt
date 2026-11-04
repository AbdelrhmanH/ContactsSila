package app.sila.feature.arham

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.AppResult
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.RecordManualContactUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * تجميع اللوحة فوق قاعدة Room حية (T8-07 · خطة §D.3-1 «قيمٌ مقابل موصّلات Room مزروعة»):
 * بذر جهات أرحام وأحداث مكالمات مؤهَّلة/غير مؤهَّلة عبر مسارات الكتابة الحقيقية، ثم
 * مطابقة كل إحصاء حرفياً — لا `size*2` ولا `540` مُختلَقَين، والموقوفون بعدد، والنافذة
 * بالساعة الثابتة.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamDashboardAggregationTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val baseMillis = 1_760_000_000_000L
    private val day = 86_400_000L
    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(baseMillis)
    }

    private fun arhamContact(
        id: Long,
        displayName: String,
        e164: String,
        frequency: ArhamFrequency,
        lastContactedDaysAgo: Int? = null,
        pausedInDays: Int? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = e164.removePrefix("+"), e164 = e164, isPrimary = true)),
        category = Category.WORK, // يُوحَّد إلى FAMILY مع upsertArhamInfo
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = frequency,
            lastContactedAt = lastContactedDaysAgo?.let { baseMillis - it * day },
            pausedUntil = pausedInDays?.let { baseMillis + it * day },
        ),
    )

    /** حدث مكتمل بدليل منصي كامل عبر مسار الكتابة الحقيقي (قاعدة AC-8 تُقيَّم في النطاق). */
    private fun evidencedCall(
        id: String,
        contactId: Long,
        timestamp: Long,
        durationSec: Int,
    ) = CallEvent(
        id = id,
        primarySource = CallSource.CALL_LOG,
        evidenceSources = setOf(CallSource.CALL_LOG),
        timestamp = timestamp,
        remoteNumber = "+20100000$id",
        contactId = contactId,
        direction = CallDirection.OUT,
        startedAt = timestamp,
        connectedAt = timestamp + 5_000,
        endedAt = timestamp + 5_000 + durationSec * 1_000L,
        durationSec = durationSec,
        outcome = CallOutcome.COMPLETED,
        association = CallAssociation.AUTO,
        confirmation = CallConfirmation.CONFIRMED,
    )

    @Test
    fun aggregation_overLiveRoom_matchesSeededFixtures_exactly() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // جهة 10: أسبوعية، تواصلت قبل 8 أيام (مستحقة) · جهة 11: أسبوعية، قبل يوم (غير مستحقة)
            // جهة 12: شهرية بلا تواصل مطلقاً (مستحقة دوماً) — مقام الهدف {10, 12} = 2.
            db.upsertContact(arhamContact(10L, "العم خالد", "+201000000010", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 8))
            db.upsertContact(arhamContact(11L, "العمة سعاد", "+201000000011", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 1))
            db.upsertContact(arhamContact(12L, "ابن العم ياسر", "+201000000012", ArhamFrequency.MONTHLY))
            db.upsertArhamInfo(10L, arhamContact(10L, "", "", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 8).arham!!)
            db.upsertArhamInfo(11L, arhamContact(11L, "", "", ArhamFrequency.WEEKLY, lastContactedDaysAgo = 1).arham!!)
            db.upsertArhamInfo(12L, arhamContact(12L, "", "", ArhamFrequency.MONTHLY).arham!!)

            val arhamRepo = ArhamRepositoryImpl(db)
            val callEventRepo = ArhamCallEventRepositoryImpl(db)
            val getStats = GetArhamDashboardStatsUseCase(
                arhamRepository = arhamRepo,
                callEventRepository = callEventRepo,
                clock = fixedClock,
                dispatchers = testDispatchers,
            )

            // قبل أي أحداث: كل الإحصاءات صفرية والمقام 2 (10 و12) — بسط 0.
            val empty = getStats().first()
            assertEquals(0, empty.totalFamilyCalls)
            assertEquals(0, empty.contactedRelatives)
            assertEquals(0, empty.longestCallSec)
            assertEquals(0f, empty.goalCompletion)
            assertEquals(0, empty.pausedCount)
            assertTrue(empty.topThree.isEmpty())

            // نداء ناجح موثَّق لجهة 11 (غير المستحقة) قبل 5 أيام — يُحتسب ولا يرفع الهدف إلا ½.
            callEventRepo.upsertEvent(evidencedCall("ev-a", contactId = 11L, timestamp = baseMillis - 5 * day, durationSec = 540))
            val one = getStats().first()
            assertEquals(1, one.totalFamilyCalls)
            assertEquals(1, one.contactedRelatives)
            assertEquals(540, one.longestCallSec)
            assertEquals(1f / 2f, one.goalCompletion)
            assertEquals(listOf(11L), one.topThree.map { it.contactId })
            assertEquals(1, one.topThree.first().rank)

            // نداءان إضافيان لجهة 10 — المنصة بالنداءات: 10 (نداءان) قبل 11 (واحد).
            callEventRepo.upsertEvent(evidencedCall("ev-b", contactId = 10L, timestamp = baseMillis - 2 * day, durationSec = 120))
            callEventRepo.upsertEvent(evidencedCall("ev-c", contactId = 10L, timestamp = baseMillis - day, durationSec = 60))
            val two = getStats().first()
            assertEquals(3, two.totalFamilyCalls)
            assertEquals(2, two.contactedRelatives)
            assertEquals(540, two.longestCallSec) // MAX فوق المؤهَّل — الأحدث لا تُختلق
            assertEquals(listOf(10L, 11L), two.topThree.map { it.contactId })

            // المحاولة غير المؤهَّلة لا تُحتسب (زر الاتصال — قاعدة AC-8).
            callEventRepo.upsertEvent(
                CallEvent(
                    id = "ev-attempt",
                    primarySource = CallSource.IN_APP,
                    evidenceSources = setOf(CallSource.IN_APP),
                    timestamp = baseMillis - 3_600_000,
                    remoteNumber = "+201000000099",
                    contactId = 12L,
                    direction = CallDirection.OUT,
                    outcome = CallOutcome.ATTEMPTED,
                    association = CallAssociation.AUTO,
                ),
            )
            val afterAttempt = getStats().first()
            assertEquals(3, afterAttempt.totalFamilyCalls)
            assertEquals(2, afterAttempt.contactedRelatives)

            // توقيف جهة 12: خارج المقام، وتُعدّ في pausedCount (AC-2 «موقوف بعدد»).
            // ملاحظة حتمية: نداءا جهة 10 حدّثا lastContactedAt (قاعدة AC-8) فصارت غير
            // مستحقة، وتوقيف 12 يُخرجه — المقام الفارغ ← هدف 0f (ولا تُختلق نسبة).
            db.upsertArhamInfo(12L, arhamContact(12L, "", "", ArhamFrequency.MONTHLY, pausedInDays = 5).arham!!)
            val afterPause = getStats().first()
            assertEquals(1, afterPause.pausedCount)
            assertEquals(0f, afterPause.goalCompletion) // المقام فارغ الآن {10 غير مستحقة، 12 موقوفة}

            // المسجيل اليدوي «تواصلت معه» مؤهَّل (CONFIRMED/COMPLETED) — يُحتسب.
            val manual = RecordManualContactUseCase(repository = callEventRepo)
            val result = manual(
                eventId = "ev-manual",
                contactId = 12L,
                timestampMillis = baseMillis - 2 * 3_600_000,
                remoteNumber = "+201000000012",
            )
            assertIs<AppResult.Success<*>>(result)
            val afterManual = getStats().first()
            assertEquals(4, afterManual.totalFamilyCalls)
            assertEquals(3, afterManual.contactedRelatives)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun observeEventsBetween_filtersByWindow_reactively() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val callEventRepo = ArhamCallEventRepositoryImpl(db)
            callEventRepo.upsertEvent(evidencedCall("ev-in", contactId = 1L, timestamp = baseMillis, durationSec = 30))
            callEventRepo.upsertEvent(
                evidencedCall("ev-old", contactId = 1L, timestamp = baseMillis - 40 * day, durationSec = 30),
            )

            val week = callEventRepo.observeEventsBetween(
                fromMillis = baseMillis - 7 * day,
                toMillis = baseMillis,
            ).first()
            assertEquals(listOf("ev-in"), week.map { it.id }) // الأحدث أولاً — خارج النافذة مُستبعد
        } finally {
            db.closeConnection()
        }
    }
}
