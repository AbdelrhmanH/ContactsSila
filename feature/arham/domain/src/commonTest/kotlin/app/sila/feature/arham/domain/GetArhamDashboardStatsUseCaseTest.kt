package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * اختبارات تجميع اللوحة فوق أحداث حقيقية (T8-07 · خطة §D.3-1/§D.3-2 · AC-2):
 * لا `size*2` ولا `540` مُختلَقَين — العدّ فوق الأحداث المؤهَّلة بقاعدة AC-8 حصراً
 * (آلة الحالات المصدر الوحيد)، المنصة الثلاثية بالنداءات الناجحة ثم المدة (O-9)،
 * والموقوفون مخفيون بعدد (AC-2). قيمُ النافذة مثبتة بساعة ثابتة.
 *
 * **هدف الفترة (FA-001):** الصيغة المعيارية من مواصفة §06 سطر 56 — نسبة الأقارب
 * (غير المناسبات وغير الموقوفين) في حالة `OK`، مشتقة من `calculateUrgency`/
 * `toArhamStatus` الموحّدتين؛ القيمة ضمن [0,1] دوماً والمقام الفارغ ⇒ 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetArhamDashboardStatsUseCaseTest {

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit
    }

    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        val events = linkedMapOf<String, CallEvent>()
        var lastWindow: Pair<Long, Long>? = null

        override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]

        override fun observePendingConfirmations(): Flow<List<CallEvent>> =
            MutableStateFlow(events.values.filter { it.confirmation == CallConfirmation.PENDING })

        override fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> {
            lastWindow = fromMillis to toMillis
            return MutableStateFlow(events.values.filter { it.timestamp in fromMillis..toMillis })
        }

        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
            events.values.filter { it.timestamp in fromMillis..toMillis }

        override suspend fun upsertEvent(event: CallEvent) {
            events[event.id] = event
        }

        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) = Unit
    }

    private val unconfined = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val baseMillis = 1_760_000_000_000L
    private val clock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(baseMillis)
    }

    private fun contact(
        id: Long,
        frequency: ArhamFrequency = ArhamFrequency.WEEKLY,
        lastContactedDaysAgo: Int? = null,
        pausedInDays: Int? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = "ق$id"),
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = frequency,
            lastContactedAt = lastContactedDaysAgo?.let { baseMillis - it * 86_400_000L },
            pausedUntil = pausedInDays?.let { baseMillis + it * 86_400_000L },
        ),
    )

    /** حدث مكتمل بدليل منصي كامل — مؤهَّل قاعدة AC-8 عند ربطه بجهة. */
    private fun completedEvent(
        id: String,
        contactId: Long,
        timestamp: Long = baseMillis - 86_400_000L,
        durationSec: Int? = 120,
    ) = CallEvent(
        id = id,
        primarySource = CallSource.CALL_LOG,
        evidenceSources = setOf(CallSource.CALL_LOG),
        timestamp = timestamp,
        remoteNumber = "+20100000000$id",
        contactId = contactId,
        direction = CallDirection.OUT,
        startedAt = timestamp,
        connectedAt = timestamp + 5_000,
        endedAt = timestamp + 5_000 + (durationSec ?: 0) * 1_000L,
        durationSec = durationSec,
        outcome = CallOutcome.COMPLETED,
        association = CallAssociation.AUTO,
        confirmation = CallConfirmation.CONFIRMED,
    )

    private fun useCase(
        repo: FakeArhamRepository,
        eventRepo: FakeArhamCallEventRepository,
    ) = GetArhamDashboardStatsUseCase(
        arhamRepository = repo,
        callEventRepository = eventRepo,
        clock = clock,
        dispatchers = unconfined,
    )

    @Test
    fun emptyContacts_zeroedStats() = runTest {
        val stats = useCase(FakeArhamRepository(emptyList()), FakeArhamCallEventRepository())().first()
        assertEquals(ArhamPeriod.WEEK, stats.period)
        assertEquals(0, stats.totalFamilyCalls)
        assertEquals(0, stats.longestCallSec)
        assertEquals(0, stats.contactedRelatives)
        assertEquals(0f, stats.goalCompletion)
        assertEquals(0, stats.pausedCount)
        assertTrue(stats.topThree.isEmpty())
    }

    @Test
    fun window_isRollingEndingAtNow_inclusiveBounds() = runTest {
        val eventRepo = FakeArhamCallEventRepository()
        eventRepo.events["ev-now"] = completedEvent("ev-now", contactId = 1L, timestamp = baseMillis)
        eventRepo.events["ev-start"] = completedEvent(
            "ev-start", contactId = 1L, timestamp = baseMillis - 7 * 86_400_000L,
        )
        eventRepo.events["ev-out"] = completedEvent(
            "ev-out", contactId = 1L, timestamp = baseMillis - 7 * 86_400_000L - 1,
        )
        val stats = useCase(FakeArhamRepository(listOf(contact(1L))), eventRepo)().first()

        assertEquals(baseMillis - 7 * 86_400_000L to baseMillis, eventRepo.lastWindow)
        assertEquals(2, stats.totalFamilyCalls) // الطرفان شاملان؛ خارج النافذة مُستبعد
    }

    @Test
    fun monthWindow_isThirtyDays() = runTest {
        val eventRepo = FakeArhamCallEventRepository()
        eventRepo.events["ev-20d"] = completedEvent(
            "ev-20d", contactId = 1L, timestamp = baseMillis - 20 * 86_400_000L,
        )
        val stats = useCase(FakeArhamRepository(listOf(contact(1L))), eventRepo)(ArhamPeriod.MONTH).first()
        assertEquals(1, stats.totalFamilyCalls)
        assertEquals(baseMillis - 30 * 86_400_000L to baseMillis, eventRepo.lastWindow)
    }

    @Test
    fun qualifyingEvents_countExactly_notSizeTimesTwo() = runTest {
        val eventRepo = FakeArhamCallEventRepository().apply {
            events["ev-1"] = completedEvent("ev-1", contactId = 1L)
            events["ev-2"] = completedEvent("ev-2", contactId = 2L)
            events["ev-3"] = completedEvent("ev-3", contactId = 1L)
        }
        val stats = useCase(FakeArhamRepository(listOf(contact(1L), contact(2L))), eventRepo)().first()
        assertEquals(3, stats.totalFamilyCalls) // ليس size*2 (كان 8 في العيب D.3-1)
        assertEquals(2, stats.contactedRelatives) // جهتان متميزتان حصراً
    }

    @Test
    fun nonQualifyingEvents_excluded_evenWhenLinkedToArhamContact() = runTest {
        val eventRepo = FakeArhamCallEventRepository().apply {
            // محاولة بلا دليل (زر الاتصال) — لا تُحتسب
            events["ev-attempt"] = CallEvent(
                id = "ev-attempt",
                primarySource = CallSource.IN_APP,
                evidenceSources = setOf(CallSource.IN_APP),
                timestamp = baseMillis - 3_600_000,
                remoteNumber = "+201000000001",
                contactId = 1L,
                direction = CallDirection.OUT,
                outcome = CallOutcome.ATTEMPTED,
                association = CallAssociation.AUTO,
            )
            // بلا ربط جهة — لا تُحتسب
            events["ev-unlinked"] = completedEvent("ev-unlinked", contactId = 99L)
            // مرفوض رغم الدليل الكامل — الرفض يعلو (قاعدة AC-8)
            events["ev-rejected"] = completedEvent("ev-rejected", contactId = 1L)
                .copy(confirmation = CallConfirmation.REJECTED)
        }
        val stats = useCase(FakeArhamRepository(listOf(contact(1L))), eventRepo)().first()
        assertEquals(0, stats.totalFamilyCalls)
        assertEquals(0, stats.contactedRelatives)
        assertEquals(0, stats.longestCallSec)
        assertTrue(stats.topThree.isEmpty())
    }

    @Test
    fun longestCallSec_isMaxQualifyingDuration_nullsExcluded_neverFabricated() = runTest {
        val eventRepo = FakeArhamCallEventRepository().apply {
            events["ev-540"] = completedEvent("ev-540", contactId = 1L, durationSec = 540)
            events["ev-120"] = completedEvent("ev-120", contactId = 1L, durationSec = 120)
            // مدة صفر بلا دليل اتصال حقيقي — غير مؤهَّلة أصلاً (قاعدة AC-8)
            events["ev-zero"] = completedEvent("ev-zero", contactId = 1L, durationSec = 0)
            // مؤهَّل بالتأكيد الصريح ومدته null — لا تُشتق ولا تُحتسب في MAX
            events["ev-null"] = CallEvent(
                id = "ev-null",
                primarySource = CallSource.MANUAL,
                evidenceSources = setOf(CallSource.MANUAL),
                timestamp = baseMillis - 86_400_000L,
                remoteNumber = "+201000000009",
                contactId = 1L,
                outcome = CallOutcome.COMPLETED,
                association = CallAssociation.MANUAL,
                confirmation = CallConfirmation.CONFIRMED,
                confidence = CallConfidence.USER_CONFIRMED,
            )
        }
        val repo = FakeArhamRepository(listOf(contact(1L)))
        assertEquals(540, useCase(repo, eventRepo)().first().longestCallSec)

        eventRepo.events.remove("ev-540")
        assertEquals(120, useCase(repo, eventRepo)().first().longestCallSec) // التالي، ليس 540

        eventRepo.events.remove("ev-120")
        assertEquals(0, useCase(repo, eventRepo)().first().longestCallSec) // بلا مدد موثوقة
    }

    @Test
    fun goalCompletion_specFormula_fractionOfEligibleInOkState() = runTest {
        val eventRepo = FakeArhamCallEventRepository()
        // جهة 1: تواصلت قبل يوم (أسبوعية ⇒ urgency 1/7 ‹ 0.7) — OK.
        // جهة 2: قبل 8 أيام ⇒ OVERDUE. جهة 5: قبل 6 أيام ⇒ 6/7 ≈ 0.857 ⇒ SOON.
        // جهة 3 موقوفة (خارج المقام)؛ جهة 4 «ب المناسبات» (لا إيقاع — خارج المقام).
        val repo = FakeArhamRepository(
            listOf(
                contact(1L, lastContactedDaysAgo = 1),
                contact(2L, lastContactedDaysAgo = 8),
                contact(3L, pausedInDays = 5),
                contact(4L, frequency = ArhamFrequency.OCCASIONS, lastContactedDaysAgo = 100),
                contact(5L, lastContactedDaysAgo = 6),
            ),
        )
        val stats = useCase(repo, eventRepo)().first()
        // المقام: 1 و2 و5 = 3 — البسط: 1 (جهة 1 فقط في OK) ⇒ 1/3 (مواصفة §06 سطر 56)
        assertEquals(1f / 3f, stats.goalCompletion)
        assertEquals(1, stats.pausedCount)
    }

    @Test
    fun goalCompletion_zeroDenominator_whenNoEligibleRelatives() = runTest {
        // كل الجهات إما موقوفة أو «ب المناسبات» — مقام فارغ ⇒ 0 بأمانة (لا 100 وهمية).
        val repo = FakeArhamRepository(
            listOf(
                contact(1L, pausedInDays = 5),
                contact(2L, frequency = ArhamFrequency.OCCASIONS, lastContactedDaysAgo = 100),
            ),
        )
        assertEquals(0f, useCase(repo, FakeArhamCallEventRepository())().first().goalCompletion)
    }

    @Test
    fun goalCompletion_allEligibleInOkState_one() = runTest {
        // ثلاث جهات مؤهَّلة كلها في OK (تواصلت قبل يوم/يومين أسبوعياً) ⇒ 3/3.
        val repo = FakeArhamRepository(
            listOf(
                contact(1L, lastContactedDaysAgo = 1),
                contact(2L, lastContactedDaysAgo = 2),
                contact(3L, lastContactedDaysAgo = 1),
            ),
        )
        assertEquals(1f, useCase(repo, FakeArhamCallEventRepository())().first().goalCompletion)
    }

    @Test
    fun goalCompletion_cannotExceedOne_whenGreensOutnumberFormerDueDenominator() = runTest {
        // انحدار FA-001: بالصيغة القديمة (متواصل/مستحق) كانت 3 خضراء غير «مستحقة» +
        // حمراء واحدة مستحقة تُنتج 300%. الصيغة المعيارية: البسط جزء من المقام ⇒ 3/4.
        val repo = FakeArhamRepository(
            listOf(
                contact(1L, lastContactedDaysAgo = 1),
                contact(2L, lastContactedDaysAgo = 1),
                contact(3L, lastContactedDaysAgo = 2),
                contact(4L, lastContactedDaysAgo = 8),
            ),
        )
        val stats = useCase(repo, FakeArhamCallEventRepository())().first()
        assertEquals(3f / 4f, stats.goalCompletion)
        assertTrue(stats.goalCompletion <= 1f)
    }

    @Test
    fun goalCompletion_noneInOkState_zero() = runTest {
        // لا أحد في OK: غير متواصل مطلقاً (OVERDUE دوماً) + متواصل قبل 8 أيام (OVERDUE).
        val repo = FakeArhamRepository(
            listOf(
                contact(1L, lastContactedDaysAgo = null),
                contact(2L, lastContactedDaysAgo = 8),
            ),
        )
        assertEquals(0f, useCase(repo, FakeArhamCallEventRepository())().first().goalCompletion)
    }

    @Test
    fun goalCompletion_neverContacted_isOverdue_inDenominatorNotNumerator() = runTest {
        // غير المتواصل مطلقاً: urgency = MAX_VALUE ⇒ OVERDUE — يدخل المقام ولا يدخل البسط.
        val repo = FakeArhamRepository(listOf(contact(1L, lastContactedDaysAgo = null)))
        assertEquals(0f, useCase(repo, FakeArhamCallEventRepository())().first().goalCompletion)
        // جهة متواصلة قبل يوم (أسبوعية): OK ⇒ 1/1 — الحالة مشتقة من آخر تواصل محفوظ.
        val contacted = FakeArhamRepository(listOf(contact(1L, lastContactedDaysAgo = 1)))
        assertEquals(1f, useCase(contacted, FakeArhamCallEventRepository())().first().goalCompletion)
    }

    @Test
    fun goalCompletion_pausedUntilExactlyNow_countsAsNotPaused() = runTest {
        // حدّ التوقيف: pausedUntil == الآن (pausedInDays = 0) — شرط الاستبعاد صراحةً
        // «paused > now» فلا يُستبعد (دلالة `calculateUrgency` و`pausedCount نفسها) —
        // يدخل المقام، وحالته OK (قبل يوم أسبوعياً) ⇒ 1/1.
        val repo = FakeArhamRepository(listOf(contact(1L, lastContactedDaysAgo = 1, pausedInDays = 0)))
        val stats = useCase(repo, FakeArhamCallEventRepository())().first()
        assertEquals(0, stats.pausedCount)
        assertEquals(1f, stats.goalCompletion)
    }

    @Test
    fun goalCompletion_dailyFrequency_dueExactlyNow_isOverdue() = runTest {
        // حدّ التكرار اليومي: تواصل قبل يوم بالضبط ⇒ urgency = 1/1 = 1.0 ⇒ OVERDUE
        // (الحد ≥ 1.0) ⇒ 0 من مقام 1 — «مستحق الآن» ليس في OK.
        val due = FakeArhamRepository(listOf(contact(1L, frequency = ArhamFrequency.DAILY, lastContactedDaysAgo = 1)))
        assertEquals(0f, useCase(due, FakeArhamCallEventRepository())().first().goalCompletion)
        // التواصل اليوم (قبل 0 أيام) ⇒ urgency = 0 ⇒ OK: {OK, OVERDUE} ⇒ 1/2.
        val mixed = FakeArhamRepository(
            listOf(
                contact(1L, frequency = ArhamFrequency.DAILY, lastContactedDaysAgo = 0),
                contact(2L, frequency = ArhamFrequency.DAILY, lastContactedDaysAgo = 1),
            ),
        )
        assertEquals(1f / 2f, useCase(mixed, FakeArhamCallEventRepository())().first().goalCompletion)
    }

    @Test
    fun podium_topThreeBySuccessfulCallsThenDuration_deterministicTieBreak() = runTest {
        val eventRepo = FakeArhamCallEventRepository().apply {
            // جهة 1: نداءان ناجحان (مجموع مدة 240)
            events["ev-a"] = completedEvent("ev-a", contactId = 1L, durationSec = 120)
            events["ev-b"] = completedEvent("ev-b", contactId = 1L, durationSec = 120)
            // جهة 2: نداء واحد أطول (مدة 600) — تسبقها جهة 1 بالعدد
            events["ev-c"] = completedEvent("ev-c", contactId = 2L, durationSec = 600)
            // جهة 3 و4: نداء واحد لكل (مدة متساوية 60) — التسوية بالمعرف الصاعد
            events["ev-d"] = completedEvent("ev-d", contactId = 4L, durationSec = 60)
            events["ev-e"] = completedEvent("ev-e", contactId = 3L, durationSec = 60)
        }
        val stats = useCase(
            FakeArhamRepository(listOf(contact(1L), contact(2L), contact(3L), contact(4L))),
            eventRepo,
        )().first()
        assertEquals(listOf(1L, 2L, 3L), stats.topThree.map { it.contactId }) // 4 خرجت بالتسوية
        assertEquals(listOf(1, 2, 3), stats.topThree.map { it.rank })         // مراتب المنصة 01..03
    }

    @Test
    fun podium_emptyWhenZeroQualifyingCalls_includesPausedContactWhenActive() = runTest {
        // بلا نداءات ناجحة: لا منصة صادقة (قائمة فارغة — قرار مثبت بالاختبار)
        val noCalls = useCase(
            FakeArhamRepository(listOf(contact(1L), contact(2L))),
            FakeArhamCallEventRepository(),
        )().first()
        assertTrue(noCalls.topThree.isEmpty())

        // موقوفٌ حالياً له نداء ناجح في الفترة يدخل المنصة (نشاط لا إلحاح)
        val eventRepo = FakeArhamCallEventRepository().apply {
            events["ev-1"] = completedEvent("ev-1", contactId = 1L)
        }
        val withPaused = useCase(
            FakeArhamRepository(listOf(contact(1L, pausedInDays = 3), contact(2L))),
            eventRepo,
        )().first()
        assertEquals(listOf(1L), withPaused.topThree.map { it.contactId })
    }
}
