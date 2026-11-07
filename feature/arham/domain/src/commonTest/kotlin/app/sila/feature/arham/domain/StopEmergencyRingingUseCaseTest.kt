package app.sila.feature.arham.domain

import app.sila.core.model.RingingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات إيقاف جلسة الطوارئ (T8-10 · §N.8 · Issue #29) — الانتقال النهائي الوحيد
 * `STOPPED_BY_USER` idempotent من كل المداخل (الإشعار/الواجهة/زر الودجت لاحقاً).
 */
class StopEmergencyRingingUseCaseTest {

    private suspend fun startedHarness(): Quad<StopEmergencyRingingUseCase, FakeEmergencySessionRepository, FakeEmergencyController, Long> {
        val clock = FixedEmergencyClock()
        val repository = FakeEmergencySessionRepository(
            clock = clock,
            contactsById = mapOf(42L to emergencyTestContact()),
        )
        val controller = FakeEmergencyController()
        StartEmergencyRingingUseCase(repository, controller, clock)(contactId = 42L)
        val sessionId = repository.liveSession!!.id
        return Quad(StopEmergencyRingingUseCase(repository, controller, clock), repository, controller, sessionId)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun explicitId_finishesStoppedByUser_andStops() = runTest {
        val (stop, repository, controller, sessionId) = startedHarness()

        val finished = stop(sessionId = sessionId)

        assertTrue(finished)
        assertEquals(listOf(sessionId to RingingStatus.STOPPED_BY_USER), repository.finishedStatuses)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun nullId_resolvesInProgressSession() = runTest {
        val (stop, repository, controller, sessionId) = startedHarness()

        val finished = stop(sessionId = null)

        assertTrue(finished)
        assertEquals(listOf(sessionId to RingingStatus.STOPPED_BY_USER), repository.finishedStatuses)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun doubleStop_isIdempotent_secondReturnsFalse() = runTest {
        val (stop, repository, _, sessionId) = startedHarness()

        assertTrue(stop(sessionId = sessionId))
        // الحسم الثاني: الصف لم يعد IN_PROGRESS ⇒ false (بلا كتابة تكرارية).
        assertFalse(stop(sessionId = sessionId))
        assertEquals(1, repository.finishedStatuses.size)
    }

    @Test
    fun nullIdWithNoSession_returnsFalse_noStopCall() = runTest {
        val clock = FixedEmergencyClock()
        val repository = FakeEmergencySessionRepository(clock = clock)
        val controller = FakeEmergencyController()
        val stop = StopEmergencyRingingUseCase(repository, controller, clock)

        assertFalse(stop(sessionId = null))
        assertEquals(0, controller.stopCount)
    }
}
