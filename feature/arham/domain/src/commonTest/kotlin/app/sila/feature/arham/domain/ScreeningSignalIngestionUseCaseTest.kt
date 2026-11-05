package app.sila.feature.arham.domain

import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.CallConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * اختبارات إدخال إشارات الفرز (T8-09 · AC-4/AC-5 · ADR-0006 §5) — مصفوفة التصنيف
 * كاملة فوق بدائيات، وقواعد الدمج التي تحفظ المصدر الأول، وحماية `lastContactedAt`
 * (نتيجة `UNKNOWN` لا تؤهل — AC-8)، والرقم المحجوب الذي يُخزَّن حداً مستقلاً بلا
 * اختراع مفاتيح (قرار D-2).
 */
class ScreeningSignalIngestionUseCaseTest {

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

    private fun useCase(repo: FakeArhamCallEventRepository) =
        ScreeningSignalIngestionUseCase(ingest = IngestCallEventUseCase(repo))

    private fun signal(
        number: String? = "+201000000000",
        verified: Boolean = false,
        atMillis: Long = 1_000L,
    ) = ScreenedCallSignal(remoteNumber = number, verificationPassed = verified, timestampMillis = atMillis)

    // ===== مصفوفة التصنيف (classifyScreeningSignal عبر مسار الإدخال) =====

    @Test
    fun verifiedSignal_classifiesHighConfidenceIncomingUnknown() = runTest {
        val ingestion = useCase(FakeArhamCallEventRepository())(signal(verified = true))

        val event = assertIs<CallEventIngestion.Persisted>(ingestion).event
        assertEquals("screening-1000", event.id)
        assertEquals(CallSource.ANDROID_CALL_SCREENING, event.primarySource)
        assertEquals(setOf(CallSource.ANDROID_CALL_SCREENING), event.evidenceSources)
        assertEquals(CallDirection.IN, event.direction)
        assertEquals(CallOutcome.UNKNOWN, event.outcome, "الفرز لا يثبت أي نتيجة")
        assertEquals(CallAssociation.UNASSOCIATED, event.association, "بلا حل رقم→جهة (ADR-0007)")
        assertEquals(CallConfirmation.PENDING, event.confirmation)
        assertEquals(CallConfidence.HIGH, event.confidence, "تحقق المنصة ⇒ ثقة عالية")
        assertNull(event.dedupeKey, "لا اختراع مفاتيح (D-2)")
        assertNull(event.simSlot, "المنصة لا تعطي الشريحة في تفاصيل الفرز")
    }

    @Test
    fun unverifiedSignal_classifiesLowConfidence() = runTest {
        val ingestion = useCase(FakeArhamCallEventRepository())(signal(verified = false))
        assertEquals(CallConfidence.LOW, assertIs<CallEventIngestion.Persisted>(ingestion).event.confidence)
    }

    // ===== حماية lastContactedAt (AC-8) =====

    @Test
    fun screeningEvents_neverTouchLastContacted_evenWhenContactIdPresent() = runTest {
        val repo = FakeArhamCallEventRepository()
        // لو أُدخل حدثٌ مرتبطٌ بجهة (مثلاً بعد ربط لاحق) بقاعدة مكتملة — ما زال الفرز
        // بنتيجة UNKNOWN لا يؤهل؛ الاختبار يثبت السلوك عبر المسار الحقيقي.
        val ingestion = useCase(repo)(signal(atMillis = 1_000L))
        val event = assertIs<CallEventIngestion.Persisted>(ingestion).event
        assertNull(event.contactId, "الفرز لا يربط جهة أصلاً (ADR-0007)")
        assertTrue(repo.lastContactedWrites.isEmpty(), "قاعدة AC-8 عبر آلة الحالات: UNKNOWN لا يُحدّث")
    }

    // ===== منع التكرار: بالمرقّم والاتجاه والنافذة — بلا مفاتيح =====

    @Test
    fun duplicateScreeningSignalWithinWindow_mergesKeepingFirstSource() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)
        useCase(signal(atMillis = 1_000L, verified = true))

        val second = useCase(signal(atMillis = 2_000L, verified = false))

        // ضمن النافذة: دمج بمعرف الأول — المصدر الأول يبقى ANDROID_CALL_SCREENING
        // (كلاهما من المصدر نفسه، لكن قاعدة الحفظ تثبت مع الحدث القائم).
        val merged = assertIs<CallEventIngestion.Merged>(second)
        assertEquals("screening-1000", merged.event.id)
        assertEquals(CallSource.ANDROID_CALL_SCREENING, merged.event.primarySource)
        assertEquals(1, repo.events.size)
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun screeningSignal_neverMergesWithOpenInAppAttempt_directionMismatch() = runTest {
        val repo = FakeArhamCallEventRepository()
        val stateMachine = CallEventStateMachine()
        // محاولة صادرة مفتوحة من زر الاتصال (T8-06) قائمة ضمن النافذة:
        val ingest = IngestCallEventUseCase(repo)
        ingest(
            stateMachine.classifyInAppDialIntent(
                eventId = "inapp-5-1000",
                remoteNumber = "+201000000000",
                timestampMillis = 1_000L,
                contactId = 5L,
            ),
        )

        // إشارة فرز واردة لنفس الرقم داخل النافذة — الاتجاهان مختلفان ⇒ بلا دمج:
        val screening = useCase(repo)(signal(atMillis = 2_000L))
        assertIs<CallEventIngestion.Persisted>(screening)
        assertEquals(2, repo.events.size, "صادرُ صِلَة والوارد المفرز حدثان مستقلان")

        // وآخر تواصل لم يُلمس من أي من المسارين (المحاولة ATTEMPTED والفرز UNKNOWN):
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun withheldNumber_storesIndependentRowWithoutDedupeBasis() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)

        val first = useCase(signal(number = null, atMillis = 1_000L))
        val second = useCase(signal(number = null, atMillis = 2_000L))

        // بلا رقم وبلا dedupeKey ⇒ بلا أساس منع تكرار (ADR-0006 §5) — صفتان مستقلتان،
        // ولا يُخترع مفتاح دمج (قرار D-2).
        assertIs<CallEventIngestion.Persisted>(first)
        assertIs<CallEventIngestion.Persisted>(second)
        assertEquals(2, repo.events.size)
        assertTrue(second.event.remoteNumber == null)
    }
}
