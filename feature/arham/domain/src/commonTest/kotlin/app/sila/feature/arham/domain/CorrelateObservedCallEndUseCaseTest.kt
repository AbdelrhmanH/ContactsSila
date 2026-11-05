package app.sila.feature.arham.domain

import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.CallConfirmation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * اختبارات ربط نهاية المرصود بمحاولة صِلَة المفتوحة (T8-09 · D-2 · AC-17) — مصفوفة
 * fail-safe كاملة: صفر مرشحين أو غموض ⇒ لا كتابة، قاعدةُ التمييز بالرنين تمنع اختلاق
 * `CONNECTED` لمكالمة واردة مجابة، والإثراء الوحيد يوحد الدليل ولا يلمس
 * `lastContactedAt` (`CONNECTED` لا يؤهل — AC-8).
 */
class CorrelateObservedCallEndUseCaseTest {

    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        val events = linkedMapOf<String, CallEvent>()
        val lastContactedWrites = mutableListOf<Pair<Long, Long>>()

        override fun observePendingConfirmations(): Flow<List<CallEvent>> =
            MutableStateFlow(events.values.filter { it.confirmation == CallConfirmation.PENDING })

        override fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> =
            MutableStateFlow(events.values.filter { it.timestamp in fromMillis..toMillis })

        override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]

        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
            events.values.filter { it.timestamp in fromMillis..toMillis }

        override suspend fun upsertEvent(event: CallEvent) {
            events[event.id] = event
        }

        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
            lastContactedWrites += contactId to timestampMillis
        }
    }

    private val stateMachine = CallEventStateMachine()

    private fun useCase(repo: FakeArhamCallEventRepository) =
        CorrelateObservedCallEndUseCase(repository = repo, stateMachine = stateMachine)

    /** يبذر محاولة صادرة مفتوحة (ATTEMPTED، بلا نهاية) وقتها بداية الدوران المُمرَّر. */
    private suspend fun seedOpenAttempt(
        repo: FakeArhamCallEventRepository,
        startedAtMillis: Long,
        contactId: Long = 5L,
    ): CallEvent {
        val attempt = stateMachine.classifyInAppDialIntent(
            eventId = "inapp-$contactId-$startedAtMillis",
            remoteNumber = "+201000000000",
            timestampMillis = startedAtMillis,
            contactId = contactId,
        )
        repo.upsertEvent(attempt)
        return attempt
    }

    // ===== fail-safe: صفر / غموض / مرشح غير مؤهل =====

    @Test
    fun zeroOpenAttempts_writesNothing() = runTest {
        val repo = FakeArhamCallEventRepository()
        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 1_000L, idleAtMillis = 61_000L))
        assertEquals(ObservedCallEndCorrelation.NoOpenAttempt, result)
        assertTrue(repo.events.isEmpty() && repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun twoOpenAttempts_ambiguous_writesNothing() = runTest {
        val repo = FakeArhamCallEventRepository()
        seedOpenAttempt(repo, startedAtMillis = 1_000L, contactId = 5L)
        seedOpenAttempt(repo, startedAtMillis = 2_000L, contactId = 6L)

        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 3_000L, idleAtMillis = 63_000L))

        val ambiguous = assertIs<ObservedCallEndCorrelation.Ambiguous>(result)
        assertEquals(2, ambiguous.candidates.size)
        assertEquals(2, repo.events.size, "بلا كتابة — الغموض لا يُحل بصمت (ADR-0006 §5)")
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun alreadyEnrichedAttempt_doesNotMatch_openFilter() = runTest {
        val repo = FakeArhamCallEventRepository()
        // محاولة أُثرت سابقاً (CONNECTED) — فلتر «المفتوحة ATTEMPTED» يستبعدها:
        val enriched = stateMachine.onInAppCallEnded(
            observed = seedOpenAttempt(repo, startedAtMillis = 1_000L),
            offhookAtMillis = 2_000L,
            idleAtMillis = 62_000L,
        )
        repo.upsertEvent(enriched)

        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 3_000L, idleAtMillis = 63_000L))

        assertEquals(ObservedCallEndCorrelation.NoOpenAttempt, result)
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun attemptOutsideLivenessWindow_writesNothing() = runTest {
        val repo = FakeArhamCallEventRepository()
        // المحاولة بدأت قبل نافذة الحياة (الافتراضية 900_000): offhook 3_000_000 ⇒
        // بداية النافذة 2_100_000 — والمحاولة عند 1_000_000 خارجة:
        seedOpenAttempt(repo, startedAtMillis = 1_000_000L)

        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 3_000_000L, idleAtMillis = 3_060_000L))
        assertEquals(ObservedCallEndCorrelation.NoOpenAttempt, result)
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    // ===== قاعدة التمييز بالرنين (منع اختلاق CONNECTED لوارد مجاب) =====

    @Test
    fun ringingAfterAttemptStart_discardsEnd() = runTest {
        val repo = FakeArhamCallEventRepository()
        val attempt = seedOpenAttempt(repo, startedAtMillis = 1_000L)

        // رنينٌ رُصد بعد بدء المحاولة (وقد يكون «انتظار نداء» أو لبس حالات) ⇒ لا كتابة:
        val result = useCase(repo)(
            ObservedCallEnd(offhookAtMillis = 2_000L, idleAtMillis = 62_000L, ringingAtMillis = 1_500L),
        )
        assertEquals(ObservedCallEndCorrelation.RingingIntervened, result)
        assertEquals(CallOutcome.ATTEMPTED, repo.events.getValue(attempt.id).outcome, "المحاولة تبقى كما هي")
    }

    @Test
    fun ringingFromPreviousCycle_beforeAttemptStart_doesNotBlockEnrichment() = runTest {
        val repo = FakeArhamCallEventRepository()
        // رنين مكالمة سابقة (انتهت بخمولٍ بلا رفع — أُهمل) قبل أن يبدأ اتصالنا:
        val attempt = seedOpenAttempt(repo, startedAtMillis = 10_000L)
        // الحامل يصفّر عند الخمول، لكن لو مرّت لحظة رنينٍ أقدم من بدء المحاولة
        // (سابقةٌ لدورتها) فقاعدة التمييز لا تنطبق:
        val result = useCase(repo)(
            ObservedCallEnd(offhookAtMillis = 11_000L, idleAtMillis = 71_000L, ringingAtMillis = 500L),
        )
        val correlated = assertIs<ObservedCallEndCorrelation.Correlated>(result)
        assertEquals(attempt.id, correlated.event.id)
        assertEquals(CallOutcome.CONNECTED, correlated.event.outcome)
    }

    // ===== الإثراء الوحيد: اتحاد الدليل، بلا آخر تواصل =====

    @Test
    fun singleOpenAttemptWithoutRinging_enrichesWithObserverEvidence_neverTouchesLastContacted() = runTest {
        val repo = FakeArhamCallEventRepository()
        val attempt = seedOpenAttempt(repo, startedAtMillis = 1_000L)

        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 2_000L, idleAtMillis = 62_000L))

        val correlated = assertIs<ObservedCallEndCorrelation.Correlated>(result)
        assertEquals(attempt.id, correlated.event.id, "الإثراء يحفظ معرف المحاولة الأول")
        assertEquals(CallSource.IN_APP, correlated.event.primarySource, "المصدر الأول لا يُستبدل")
        assertEquals(CallOutcome.CONNECTED, correlated.event.outcome, "مدة تقريبية ⇒ CONNECTED لا COMPLETED")
        assertEquals(
            setOf(CallSource.IN_APP, CallSource.ANDROID_TELEPHONY_OBSERVER),
            correlated.event.evidenceSources,
            "اتحاد الدليل: المراقب شاهد النهاية (ADR-0006 §5)",
        )
        assertEquals(2_000L, correlated.event.connectedAt)
        assertEquals(62_000L, correlated.event.endedAt)
        assertEquals(60, correlated.event.durationSec)

        // الحدث القائم في المستودع هو المُثرى نفسه:
        assertEquals(CallOutcome.CONNECTED, repo.events.getValue(attempt.id).outcome)
        // AC-8: CONNECTED لا يؤهل — آخر تواصل لا يُكتب من هنا إطلاقاً:
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun enrichment_outcomeNeverCompleted_evenWithLongDuration() = runTest {
        val repo = FakeArhamCallEventRepository()
        val attempt = seedOpenAttempt(repo, startedAtMillis = 0L)
        val result = useCase(repo)(ObservedCallEnd(offhookAtMillis = 1_000L, idleAtMillis = 3_601_000L))
        val correlated = assertIs<ObservedCallEndCorrelation.Correlated>(result)
        assertEquals(CallDirection.OUT, correlated.event.direction)
        assertEquals(
            CallOutcome.CONNECTED,
            correlated.event.outcome,
            "حتى بساعةٍ من المدة: المدة التقريبية لا تثبت الرد — COMPLETED بتأكيد المستخدم فقط",
        )
        assertTrue(repo.lastContactedWrites.isEmpty())
    }
}
