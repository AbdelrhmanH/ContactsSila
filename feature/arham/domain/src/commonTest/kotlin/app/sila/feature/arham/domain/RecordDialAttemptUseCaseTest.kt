package app.sila.feature.arham.domain

import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * اختبارات تسجيل نية الاتصال (T8-06 · خطة §D.3-4 · AC-3/AC-8): المحاولة تُخزَّن
 * `ATTEMPTED`/`PENDING` حصراً ولا تلمس آخر تواصل أبداً؛ وإعادة الاتصال تُدمج في
 * الحدث القائم مع حفظ المعرف الأول (نافذة منع التكرار الافتراضية).
 */
class RecordDialAttemptUseCaseTest {

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

    private fun useCase(repository: FakeArhamCallEventRepository) =
        RecordDialAttemptUseCase(ingest = IngestCallEventUseCase(repository))

    @Test
    fun dialIntent_persistsAttemptedPendingEvent_neverTouchesLastContacted() = runTest {
        val repo = FakeArhamCallEventRepository()

        val ingestion = useCase(repo)(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_000L)

        val event = assertIs<CallEventIngestion.Persisted>(ingestion).event
        assertEquals("inapp-5-1000", event.id) // معرف حتمي البناء
        assertEquals(CallSource.IN_APP, event.primarySource)
        assertEquals(CallOutcome.ATTEMPTED, event.outcome) // المحاولة ليست إتماماً (AC-3)
        assertEquals(CallConfirmation.PENDING, event.confirmation)
        assertEquals(CallDirection.OUT, event.direction)
        assertEquals(CallAssociation.AUTO, event.association)
        assertTrue(repo.lastContactedWrites.isEmpty()) // قاعدة AC-8: المحاولة لا تُحدّث
    }

    @Test
    fun dialIntent_carriesSimSlotWhenProvided_andStaysNullOtherwise() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)

        val withSlot = useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_000L, simSlot = 0)
        val withoutSlot = useCase(contactId = 6L, remoteNumber = "+201000000001", timestampMillis = 1_000L)

        assertEquals(0, assertIs<CallEventIngestion.Persisted>(withSlot).event.simSlot)
        // الافتراضي: null — `preferredSimSlot` غير مُخزَّن في Room v2 (EntityMappers) فلا
        // مصدر حياً للقيمة بعد؛ التوقيع جاهز لعند تخزين التفضيل (قرار D-2).
        assertNull(assertIs<CallEventIngestion.Persisted>(withoutSlot).event.simSlot)
    }

    @Test
    fun redialWithinDedupeWindow_mergesIntoExistingEvent_keepingFirstId() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)
        useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_000L)

        val second = useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 2_000L)

        // ضمن نافذة 15 دقيقة: دمج بمعرف الأول (ADR-0006 §5) — صف واحد لا اثنان.
        val merged = assertIs<CallEventIngestion.Merged>(second)
        assertEquals("inapp-5-1000", merged.event.id)
        assertEquals(1, repo.events.size)
        assertTrue(repo.lastContactedWrites.isEmpty())
    }

    @Test
    fun redialOutsideDedupeWindow_createsIndependentEvent() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)
        useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_000L)

        val later = useCase(
            contactId = 5L,
            remoteNumber = "+201000000000",
            timestampMillis = 1_000L + CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS + 1,
        )

        assertIs<CallEventIngestion.Persisted>(later)
        assertEquals(2, repo.events.size)
    }

    @Test
    fun deterministicId_distinguishesContactsAndTimestamps() = runTest {
        val repo = FakeArhamCallEventRepository()
        val useCase = useCase(repo)

        val a = useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_000L)
        val b = useCase(contactId = 6L, remoteNumber = "+201000000001", timestampMillis = 1_000L)
        val c = useCase(contactId = 5L, remoteNumber = "+201000000000", timestampMillis = 1_777_777_777_777L)

        // جهات/أرقام/أزمنة مختلفة (خارج النافذة) → أحداث مستقلة بمعرفات حتمية مختلفة.
        // (الرقم نفسه من جهة مختلفة ضمن النافذة يُدمج عمداً — منع التكرار بالرقم والاتجاه.)
        assertEquals(3, repo.events.size)
        assertEquals(
            listOf("inapp-5-1000", "inapp-6-1000", "inapp-5-1777777777777"),
            listOf(
                assertIs<CallEventIngestion.Persisted>(a).event.id,
                assertIs<CallEventIngestion.Persisted>(b).event.id,
                assertIs<CallEventIngestion.Persisted>(c).event.id,
            ),
        )
    }
}
