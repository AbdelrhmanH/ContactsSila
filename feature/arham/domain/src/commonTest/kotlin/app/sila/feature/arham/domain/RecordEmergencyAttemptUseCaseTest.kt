package app.sila.feature.arham.domain

import app.sila.core.model.RingingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات تسجيل محاولة الطوارئ (T8-10 · §N.4 · Issue #29) — إعادة قراءة الجهة كل محاولة
 * (stop-on-block/الإجحاف)، الذرية عبر [EmergencySessionRepository.recordAttempt]، والحسم
 * idempotent عند السقف.
 */
class RecordEmergencyAttemptUseCaseTest {

    private suspend fun startedHarness(): Quad<RecordEmergencyAttemptUseCase, FakeEmergencySessionRepository, FakeEmergencyController, Long> {
        val clock = FixedEmergencyClock()
        val repository = FakeEmergencySessionRepository(
            clock = clock,
            contactsById = mapOf(42L to emergencyTestContact()),
        )
        val controller = FakeEmergencyController()
        // ابدأ جلسة حقيقية أولاً (عقد الصف الوحيد).
        StartEmergencyRingingUseCase(repository, controller, clock)(contactId = 42L)
        val sessionId = repository.liveSession!!.id
        return Quad(RecordEmergencyAttemptUseCase(repository, controller, clock), repository, controller, sessionId)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun continueRinging_belowCeiling_noFinishNoStop() = runTest {
        val (record, repository, controller, sessionId) = startedHarness()

        val result = record(sessionId = sessionId, contactId = 42L)

        assertEquals(RecordEmergencyAttemptUseCase.AttemptResult.ContinueRinging(1), result)
        assertTrue(repository.finishedStatuses.isEmpty()) // لا حسم
        assertEquals(0, controller.stopCount) // الحلقة مستمرة
    }

    @Test
    fun maxReached_atTen_finishesMaxReached_andStops() = runTest {
        val (record, repository, controller, sessionId) = startedHarness()
        // 9 محاولات سابقة — هذه هي العاشرة (§C.1-8: كل الحالات تُحتسب).
        repeat(9) { repository.recordAttempt(sessionId = sessionId, atMillis = 0L) }

        val result = record(sessionId = sessionId, contactId = 42L)

        assertEquals(RecordEmergencyAttemptUseCase.AttemptResult.MaxReached, result)
        assertEquals(listOf(sessionId to RingingStatus.MAX_REACHED), repository.finishedStatuses)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun blocked_midSession_finishesBlocked_andStops_noAttemptRecorded() = runTest {
        val (record, repository, controller, sessionId) = startedHarness()
        repository.contactsById[42L] = emergencyTestContact(isBlocked = true)

        val result = record(sessionId = sessionId, contactId = 42L)

        assertEquals(RecordEmergencyAttemptUseCase.AttemptResult.Blocked, result)
        assertEquals(listOf(sessionId to RingingStatus.BLOCKED), repository.finishedStatuses)
        assertEquals(1, controller.stopCount)
        assertEquals(0, repository.liveSession?.attempts) // بلا محاولة مسجَّلة للحظر
    }

    @Test
    fun contactDeleted_midSession_finishesInterrupted_andStops() = runTest {
        val (record, repository, controller, sessionId) = startedHarness()
        repository.contactsById.remove(42L)

        val result = record(sessionId = sessionId, contactId = 42L)

        assertEquals(RecordEmergencyAttemptUseCase.AttemptResult.ContactDeleted, result)
        assertEquals(listOf(sessionId to RingingStatus.INTERRUPTED), repository.finishedStatuses)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun sessionFinished_whenRecordReturnsNull() = runTest {
        val (record, repository, _, sessionId) = startedHarness()
        repository.recordAttemptReturnsNull = true

        val result = record(sessionId = sessionId, contactId = 42L)

        assertEquals(RecordEmergencyAttemptUseCase.AttemptResult.SessionFinished, result)
    }
}
