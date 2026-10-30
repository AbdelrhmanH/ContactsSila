package app.sila.feature.contacts.domain.onboarding

import app.sila.core.model.TopFiveSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات وحدة لحالات استخدام التهيئة الأولى فوق مستودع وهمي (Dependency Inversion).
 */
class OnboardingUseCasesTest {

    private class FakeOnboardingRepository : OnboardingRepository {
        var state = OnboardingState(isGuest = false)
        private val flow = MutableStateFlow(state)

        private fun record(next: () -> Unit) {
            next()
            flow.value = state
        }

        override fun observeState(): Flow<OnboardingState> = flow.map { it }

        override suspend fun completeStep(step: OnboardingStep) = record {
            val next = step.nextStep()
            state = if (next == null) {
                state.copy(isCompleted = true, isGuest = true, resumeStep = null)
            } else {
                state.copy(resumeStep = next)
            }
        }

        override suspend fun setContactsPermission(permission: OnboardingPermissionState) = record {
            state = state.copy(contactsPermission = permission)
        }

        override suspend fun saveTopFiveSelections(selections: List<TopFiveSelection>) = record {
            state = state.copy(topFiveSelections = selections)
        }

        override suspend fun completeOnboarding() = record {
            state = state.copy(isCompleted = true, isGuest = true, resumeStep = null)
        }
    }

    @Test
    fun observeOnboardingState_emitsRepositoryState() = runTest {
        val repository = FakeOnboardingRepository()
        val observe = ObserveOnboardingStateUseCase(repository)

        assertEquals(OnboardingState(isGuest = false), observe().first())
    }

    @Test
    fun completeOnboardingStep_advancesThroughAllStepsAndFinishesOnLast() = runTest {
        val repository = FakeOnboardingRepository()
        val completeStep = CompleteOnboardingStepUseCase(repository)

        completeStep(OnboardingStep.WELCOME)
        assertEquals(OnboardingStep.PERMISSION, repository.state.resumeStep)
        assertFalse(repository.state.isCompleted)

        completeStep(OnboardingStep.PERMISSION)
        completeStep(OnboardingStep.GUEST_LOGIN)
        assertEquals(OnboardingStep.TOP_FIVE, repository.state.resumeStep)

        completeStep(OnboardingStep.TOP_FIVE)
        assertEquals(OnboardingStep.ORGANIZING, repository.state.resumeStep)

        completeStep(OnboardingStep.ORGANIZING)
        assertTrue(repository.state.isCompleted)
        assertNull(repository.state.resumeStep)
    }

    @Test
    fun setOnboardingPermissionsResult_isReflectedInState() = runTest {
        val repository = FakeOnboardingRepository()
        val setResult = SetOnboardingPermissionsResultUseCase(repository)

        setResult(OnboardingPermissionState.DENIED)
        assertEquals(OnboardingPermissionState.DENIED, repository.state.contactsPermission)

        setResult(OnboardingPermissionState.LIMITED)
        assertEquals(OnboardingPermissionState.LIMITED, repository.state.contactsPermission)
    }

    @Test
    fun saveTopFiveSelection_persistsHandoffRecordForArhamFeature() = runTest {
        val repository = FakeOnboardingRepository()
        val saveSelection = SaveTopFiveSelectionUseCase(repository)

        val selections = listOf(
            TopFiveSelection("sys_7", "والدي", Relation.FATHER),
            TopFiveSelection("sys_9", "والدتي", Relation.MOTHER)
        )
        saveSelection(selections)

        assertEquals(selections, repository.state.topFiveSelections)

        saveSelection(emptyList())
        assertTrue(repository.state.topFiveSelections.isEmpty())
    }

    @Test
    fun completeOnboarding_setsGuestAndCompletionWithoutResuming() = runTest {
        val repository = FakeOnboardingRepository()
        val complete = CompleteOnboardingUseCase(repository)

        complete()

        assertTrue(repository.state.isCompleted)
        assertTrue(repository.state.isGuest)
        assertNull(repository.state.resumeStep)
    }
}
