package app.sila.feature.arham.domain

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات حالات استخدام أحداث المكالمات فوق مستودع وهمي (Dependency Inversion):
 * الإدخال المرصود، حسم التأكيد الأربعية، والتسجيل اليدوي «تواصلت معه».
 */
class ArhamCallEventUseCasesTest {

    /** مستودع وهمي يسجّل كل كتابات آخر تواصل لإثبات قاعدة AC-8. */
    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        val events = linkedMapOf<String, CallEvent>()
        val lastContactedWrites = mutableListOf<Pair<Long, Long>>()
        var upsertCount = 0

        override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]

        override fun observePendingConfirmations(): Flow<List<CallEvent>> =
            MutableStateFlow(events.values.filter { it.confirmation == CallConfirmation.PENDING })

        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
            events.values.filter { it.timestamp in fromMillis..toMillis }

        override suspend fun upsertEvent(event: CallEvent) {
            upsertCount++
            events[event.id] = event
        }

        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
            lastContactedWrites += contactId to timestampMillis
        }
    }

    private fun screeningEvent(
        id: String,
        timestamp: Long,
        remoteNumber: String = "+201000000001",
    ) = CallEvent(
        id = id,
        primarySource = CallSource.ANDROID_CALL_SCREENING,
        evidenceSources = setOf(CallSource.ANDROID_CALL_SCREENING),
        timestamp = timestamp,
        remoteNumber = remoteNumber,
        direction = CallDirection.IN,
    )

    // region IngestCallEventUseCase

    @Test
    fun ingest_candidateWithoutDedupeBasis_isPersistedAsIndependentEvent() = runTest {
        val repository = FakeArhamCallEventRepository()
        val ingest = IngestCallEventUseCase(repository)
        // حدث مراقب بلا رقم ولا مفتاح — لا أساس للمطابقة، يُخزّن حداً مستقلاً.
        val candidate = CallEvent(
            id = "obs-1",
            primarySource = CallSource.ANDROID_TELEPHONY_OBSERVER,
            evidenceSources = setOf(CallSource.ANDROID_TELEPHONY_OBSERVER),
            timestamp = 1_000L,
        )

        val result = ingest(candidate)

        assertIs<CallEventIngestion.Persisted>(result)
        assertEquals("obs-1", repository.events.getValue("obs-1").id)
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    @Test
    fun ingest_attemptedDial_neverUpdatesLastContacted() = runTest {
        // اختبار انحدار §D.3-4 على مستوى النطاق: الاتصال من زر صِلَة لا يحدّث آخر تواصل.
        val repository = FakeArhamCallEventRepository()
        val machine = CallEventStateMachine()
        val ingest = IngestCallEventUseCase(repository)
        val candidate = machine.classifyInAppDialIntent(
            eventId = "dial-1",
            remoteNumber = "+201000000001",
            timestampMillis = 1_000L,
            contactId = 5L,
        )

        ingest(candidate)

        assertTrue(repository.lastContactedWrites.isEmpty())
        assertEquals(CallOutcome.ATTEMPTED, repository.events.getValue("dial-1").outcome)
    }

    @Test
    fun ingest_uniqueMatch_mergesIntoExistingEvent() = runTest {
        val repository = FakeArhamCallEventRepository()
        val ingest = IngestCallEventUseCase(repository)
        val observed = screeningEvent("scr-1", timestamp = 60_000L)
        repository.upsertEvent(observed)
        val candidate = observedEventCopy()
            .copy(id = "tel-1", timestamp = 90_000L)

        val result = ingest(candidate)

        assertIs<CallEventIngestion.Merged>(result)
        // لا صف ثانٍ — الدمج في الحدث القائم يحفظ الأصل (ADR-0006 §5):
        assertEquals(setOf("scr-1"), repository.events.keys)
        val merged = repository.events.getValue("scr-1")
        assertEquals(CallSource.ANDROID_CALL_SCREENING, merged.primarySource)
        assertEquals(
            setOf(CallSource.ANDROID_CALL_SCREENING, CallSource.ANDROID_TELEPHONY_OBSERVER),
            merged.evidenceSources,
        )
        assertEquals(60_000L, merged.timestamp)
    }

    @Test
    fun ingest_ambiguousMatch_writesNothing() = runTest {
        val repository = FakeArhamCallEventRepository()
        val ingest = IngestCallEventUseCase(repository)
        repository.upsertEvent(screeningEvent("scr-1", timestamp = 0L))
        repository.upsertEvent(screeningEvent("scr-2", timestamp = 60_000L))
        val candidate = observedEventCopy().copy(id = "cand", timestamp = 30_000L)

        val result = ingest(candidate)

        assertIs<CallEventIngestion.Ambiguous>(result)
        assertEquals(2, result.candidates.size)
        assertEquals(2, repository.events.size)
        assertNull(repository.events["cand"])
    }

    @Test
    fun ingest_completedConfirmedEvent_updatesLastContactedWithMaxSemanticsInput() = runTest {
        val repository = FakeArhamCallEventRepository()
        val ingest = IngestCallEventUseCase(repository)
        val completed = CallEvent(
            id = "done-1",
            primarySource = CallSource.IN_APP,
            evidenceSources = setOf(CallSource.IN_APP, CallSource.MANUAL),
            timestamp = 42_000L,
            remoteNumber = "+201000000001",
            contactId = 5L,
            connectedAt = 1_000L,
            endedAt = 41_000L,
            durationSec = 40,
            outcome = CallOutcome.COMPLETED,
            confirmation = CallConfirmation.CONFIRMED,
        )

        ingest(completed)

        assertEquals(listOf(5L to 42_000L), repository.lastContactedWrites)
    }

    // endregion

    // region ResolveConfirmationUseCase

    @Test
    fun resolve_missingEvent_returnsNotFound() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)

        val result = resolve("ghost", ConfirmationAnswer.CONTACTED)

        assertIs<AppResult.Error>(result)
        assertIs<DataError.NotFound>(result.error)
        assertEquals(0, repository.upsertCount)
    }

    @Test
    fun resolve_nonPendingEvent_returnsConflict_withoutWrites() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)
        val rejected = screeningEvent("scr-1", timestamp = 0L)
            .copy(confirmation = CallConfirmation.REJECTED)
        repository.upsertEvent(rejected)

        val result = resolve("scr-1", ConfirmationAnswer.CONTACTED)

        // الرفض نهائي — لا يُقلب قرار المستخدم ولا تُكتب بيانات:
        assertIs<AppResult.Error>(result)
        assertIs<DataError.Conflict>(result.error)
        assertEquals(1, repository.upsertCount)
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    @Test
    fun resolve_contacted_persistsConfirmedCompleted_andUpdatesLastContacted() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)
        repository.upsertEvent(screeningEvent("scr-1", timestamp = 7_000L).copy(contactId = 9L))

        val result = resolve("scr-1", ConfirmationAnswer.CONTACTED)

        assertIs<AppResult.Success<CallEvent>>(result)
        val resolved = result.data
        assertEquals(CallConfirmation.CONFIRMED, resolved.confirmation)
        assertEquals(CallOutcome.COMPLETED, resolved.outcome)
        assertTrue(CallSource.MANUAL in resolved.evidenceSources)
        assertEquals(listOf(9L to 7_000L), repository.lastContactedWrites)
    }

    @Test
    fun resolve_noAnswer_confirmsUnanswered_withoutTouchingLastContacted() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)
        repository.upsertEvent(screeningEvent("scr-1", timestamp = 7_000L).copy(contactId = 9L))

        val result = resolve("scr-1", ConfirmationAnswer.NO_ANSWER)

        assertIs<AppResult.Success<CallEvent>>(result)
        assertEquals(CallOutcome.UNANSWERED, result.data.outcome)
        assertEquals(CallConfirmation.CONFIRMED, result.data.confirmation)
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    @Test
    fun resolve_notRelated_persistsRejected_withoutTouchingLastContacted() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)
        repository.upsertEvent(screeningEvent("scr-1", timestamp = 7_000L).copy(contactId = 9L))

        val result = resolve("scr-1", ConfirmationAnswer.NOT_RELATED)

        assertIs<AppResult.Success<CallEvent>>(result)
        assertEquals(CallConfirmation.REJECTED, result.data.confirmation)
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    @Test
    fun resolve_later_keepsPending_withoutAnyWrite() = runTest {
        val repository = FakeArhamCallEventRepository()
        val resolve = ResolveConfirmationUseCase(repository)
        val pending = screeningEvent("scr-1", timestamp = 7_000L).copy(contactId = 9L)
        repository.upsertEvent(pending)

        val result = resolve("scr-1", ConfirmationAnswer.LATER)

        assertIs<AppResult.Success<CallEvent>>(result)
        assertEquals(CallConfirmation.PENDING, result.data.confirmation)
        assertEquals(1, repository.upsertCount)
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    // endregion

    // region RecordManualContactUseCase

    @Test
    fun recordManual_blankEventId_returnsValidationError() = runTest {
        val repository = FakeArhamCallEventRepository()
        val record = RecordManualContactUseCase(repository)

        val result = record(eventId = " ", contactId = 5L, timestampMillis = 0L)

        assertIs<AppResult.Error>(result)
        assertIs<DataError.Validation>(result.error)
        assertEquals(0, repository.upsertCount)
    }

    @Test
    fun recordManual_withoutNumber_isPersistedConfirmed_andUpdatesLastContacted() = runTest {
        val repository = FakeArhamCallEventRepository()
        val record = RecordManualContactUseCase(repository)

        val result = record(eventId = "man-1", contactId = 5L, timestampMillis = 10_000L)

        assertIs<AppResult.Success<CallEventIngestion>>(result)
        assertIs<CallEventIngestion.Persisted>(result.data)
        val stored = repository.events.getValue("man-1")
        assertEquals(CallSource.MANUAL, stored.primarySource)
        assertEquals(CallOutcome.COMPLETED, stored.outcome)
        assertEquals(CallConfirmation.CONFIRMED, stored.confirmation)
        assertEquals(setOf(CallSource.MANUAL), stored.evidenceSources)
        assertEquals(listOf(5L to 10_000L), repository.lastContactedWrites)
    }

    @Test
    fun recordManual_matchingObservedEvent_mergesConfirmationIntoIt() = runTest {
        val repository = FakeArhamCallEventRepository()
        val record = RecordManualContactUseCase(repository)
        val observed = screeningEvent("scr-1", timestamp = 60_000L)
        repository.upsertEvent(observed)

        val result = record(
            eventId = "man-1",
            contactId = 5L,
            timestampMillis = 90_000L,
            remoteNumber = "+201000000001",
        )

        // يُدمج التأكيد في الحدث المعلق المطابق — لا حدث ثانٍ (ADR-0006 §5):
        assertIs<AppResult.Success<CallEventIngestion>>(result)
        assertIs<CallEventIngestion.Merged>(result.data)
        assertEquals(setOf("scr-1"), repository.events.keys)
        val merged = repository.events.getValue("scr-1")
        assertEquals(CallSource.ANDROID_CALL_SCREENING, merged.primarySource)
        assertEquals(
            setOf(CallSource.ANDROID_CALL_SCREENING, CallSource.MANUAL),
            merged.evidenceSources,
        )
        assertEquals(CallConfirmation.CONFIRMED, merged.confirmation)
        assertEquals(CallOutcome.COMPLETED, merged.outcome)
        assertEquals(5L, merged.contactId)
        assertEquals(60_000L, merged.timestamp)
        assertEquals(listOf(5L to 60_000L), repository.lastContactedWrites)
    }

    @Test
    fun recordManual_doesNotMergeIntoRejectedEvent() = runTest {
        val repository = FakeArhamCallEventRepository()
        val record = RecordManualContactUseCase(repository)
        val rejected = screeningEvent("scr-1", timestamp = 60_000L)
            .copy(confirmation = CallConfirmation.REJECTED)
        repository.upsertEvent(rejected)

        val result = record(
            eventId = "man-1",
            contactId = 5L,
            timestampMillis = 90_000L,
            remoteNumber = "+201000000001",
        )

        // الرفض نهائي — التسجيل اليدوي يُخزّن حداً جديداً ولا يُحيي الحدث المرفوض:
        assertIs<AppResult.Success<CallEventIngestion>>(result)
        assertIs<CallEventIngestion.Persisted>(result.data)
        assertEquals(CallConfirmation.REJECTED, repository.events.getValue("scr-1").confirmation)
        assertEquals(CallConfirmation.CONFIRMED, repository.events.getValue("man-1").confirmation)
    }

    @Test
    fun recordManual_ambiguousMatch_writesNothing() = runTest {
        val repository = FakeArhamCallEventRepository()
        val record = RecordManualContactUseCase(repository)
        repository.upsertEvent(screeningEvent("scr-1", timestamp = 0L))
        repository.upsertEvent(screeningEvent("scr-2", timestamp = 60_000L))

        val result = record(
            eventId = "man-1",
            contactId = 5L,
            timestampMillis = 30_000L,
            remoteNumber = "+201000000001",
        )

        assertIs<AppResult.Success<CallEventIngestion>>(result)
        assertIs<CallEventIngestion.Ambiguous>(result.data)
        assertEquals(2, repository.upsertCount)
        assertNull(repository.events["man-1"])
        assertTrue(repository.lastContactedWrites.isEmpty())
    }

    // endregion

    /** حدث مراقب مرجعي لتوليد مرشّحات الدمج في الاختبارات. */
    private fun observedEventCopy(): CallEvent = CallEvent(
        id = "seed",
        primarySource = CallSource.ANDROID_TELEPHONY_OBSERVER,
        evidenceSources = setOf(CallSource.ANDROID_TELEPHONY_OBSERVER),
        timestamp = 0L,
        remoteNumber = "+201000000001",
        direction = CallDirection.IN,
    )
}
