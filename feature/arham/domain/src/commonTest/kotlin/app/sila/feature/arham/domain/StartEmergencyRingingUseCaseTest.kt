package app.sila.feature.arham.domain

import app.sila.core.model.Category
import app.sila.core.model.RingingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات بدء جلسة الطوارئ (T8-10 · AC-14 · §N.2 · Issue #29) — ترتيب البوابات:
 * AlreadyRunning ← ContactNotFound ← NotEmergencyContact ← ContactBlocked ← RateLimited ← Started.
 */
class StartEmergencyRingingUseCaseTest {

    private fun harness(
        contact: app.sila.core.model.Contact? = emergencyTestContact(),
        sessionsStartedInWindow: Int = 0,
    ): Triple<StartEmergencyRingingUseCase, FakeEmergencySessionRepository, FakeEmergencyController> {
        val clock = FixedEmergencyClock()
        val repository = FakeEmergencySessionRepository(
            clock = clock,
            contactsById = if (contact == null) emptyMap() else mapOf(contact.id to contact),
            sessionsStartedInWindow = sessionsStartedInWindow,
        )
        val controller = FakeEmergencyController()
        return Triple(
            StartEmergencyRingingUseCase(repository = repository, controller = controller, clock = clock),
            repository,
            controller,
        )
    }

    @Test
    fun started_insertsRow_andStartsControllerWithIds() = runTest {
        val (start, repository, controller) = harness()
        val result = start(contactId = 42L)

        assertTrue(result is StartEmergencyRingingUseCase.StartResult.Started)
        assertEquals(100L, (result as StartEmergencyRingingUseCase.StartResult.Started).sessionId)
        assertEquals(1, controller.startedSessions.size)
        assertEquals(100L to 42L, controller.startedSessions.first())
        assertEquals(RingingStatus.IN_PROGRESS, repository.liveSession?.status)
    }

    @Test
    fun alreadyRunning_whenSessionInProgress_idempotent() = runTest {
        val (start, repository, controller) = harness()
        start(contactId = 42L)
        repository.nextSessionId = 999L // لو بدأ ثانٍ لَأحدث صفاً جديداً — يجب ألا يحدث

        val second = start(contactId = 42L)
        assertEquals(StartEmergencyRingingUseCase.StartResult.AlreadyRunning, second)
        assertEquals(1, controller.startedSessions.size)
    }

    @Test
    fun contactNotFound_whenUnknown() = runTest {
        val (start, _, controller) = harness(contact = null)
        assertEquals(StartEmergencyRingingUseCase.StartResult.ContactNotFound, start(contactId = 7L))
        assertTrue(controller.startedSessions.isEmpty())
    }

    @Test
    fun notEmergencyContact_whenWrongCategory() = runTest {
        val (start, _, controller) = harness(
            contact = emergencyTestContact(category = Category.FAMILY),
        )
        assertEquals(StartEmergencyRingingUseCase.StartResult.NotEmergencyContact, start(contactId = 42L))
        assertTrue(controller.startedSessions.isEmpty())
    }

    @Test
    fun contactBlocked_stopOnBlockGate_atStart() = runTest {
        val (start, _, controller) = harness(
            contact = emergencyTestContact(isBlocked = true),
        )
        // قرار مُفصَح عنه (انحراف مُعلَن للمراجع): بوابة `isBlocked` **أولاً بالبداية** —
        // §N.4 يشترط stop-on-block وسط الجلسة، والبداية المحظورة تُمنع أصلاً (بلا صفٍّ يتيمة).
        assertEquals(StartEmergencyRingingUseCase.StartResult.ContactBlocked, start(contactId = 42L))
        assertTrue(controller.startedSessions.isEmpty())
    }

    @Test
    fun rateLimited_whenWindowCountAtLimit() = runTest {
        val (start, _, controller) = harness(sessionsStartedInWindow = 3)
        assertEquals(StartEmergencyRingingUseCase.StartResult.RateLimited, start(contactId = 42L))
        assertTrue(controller.startedSessions.isEmpty())
    }
}
