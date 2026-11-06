package app.sila.feature.arham.domain

import app.sila.core.model.RingingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات تسوية جلسات الطوارئ العالقة (T8-10 · §N.5/§O.7 · Issue #29) — «صف Room
 * مصدر الحقيقة»: الجلسة العالقة (بلا منفذ حي + تجاوزت حد الحيوية 30 د — قرار مُقترَح
 * مُفصَح عنه) تُحسم `INTERRUPTED` idempotent؛ المنفذ الحي لا يُمَسّ.
 */
class ReconcileInterruptedSessionUseCaseTest {

    private fun harness(): Quad<ReconcileInterruptedSessionUseCase, FakeEmergencySessionRepository, FakeEmergencyController, FixedEmergencyClock> {
        val clock = FixedEmergencyClock()
        val repository = FakeEmergencySessionRepository(
            clock = clock,
            contactsById = mapOf(42L to emergencyTestContact()),
        )
        val controller = FakeEmergencyController()
        return Quad(
            ReconcileInterruptedSessionUseCase(repository, controller, clock),
            repository,
            controller,
            clock,
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun staleOrphan_withoutLiveController_closedInterrupted() = runTest {
        val (reconcile, repository, controller, clock) = harness()
        StartEmergencyRingingUseCase(repository, controller, clock)(contactId = 42L)
        val sessionId = repository.liveSession!!.id
        controller.simulateProcessDeath() // العملية ماتت — المنفذ لا يعمل
        clock.millis += 1_800_001L // تجاوز حد الحيوية (30 دقيقة + 1ms — §N.5)

        val reconciled = reconcile()

        assertEquals(sessionId, reconciled)
        assertEquals(listOf(sessionId to RingingStatus.INTERRUPTED), repository.finishedStatuses)
    }

    @Test
    fun liveController_isNotTouched() = runTest {
        val (reconcile, repository, controller, clock) = harness()
        StartEmergencyRingingUseCase(repository, controller, clock)(contactId = 42L)

        // المنفذ يعمل بنفس الجلسة (isRunning = true) ⇒ التسوية لا تلمسه حتى لو تجاوز الحد.
        assertNull(reconcile())
        assertTrue(repository.finishedStatuses.isEmpty())
        assertEquals(RingingStatus.IN_PROGRESS, repository.liveSession?.status)
    }

    @Test
    fun freshSession_withinLivenessBound_isKept() = runTest {
        val (reconcile, repository, controller, clock) = harness()
        StartEmergencyRingingUseCase(repository, controller, clock)(contactId = 42L)
        controller.simulateProcessDeath()
        clock.millis += 1000L // ثانية واحدة فقط — ضمن حد الحيوية

        assertNull(reconcile())
        assertTrue(repository.finishedStatuses.isEmpty())
    }

    @Test
    fun noInProgressSession_isNoOp() = runTest {
        val (reconcile, repository, _, _) = harness()

        assertNull(reconcile())
        assertTrue(repository.finishedStatuses.isEmpty())
    }
}
