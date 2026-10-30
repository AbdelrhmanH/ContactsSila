package app.sila.feature.contacts.data

import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.domain.onboarding.OnboardingStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingRepositoryImplTest {

    @Test
    fun observeState_defaultsToWelcomeStepGuestNotCompleted() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        val state = repository.observeState().first()

        assertEquals(OnboardingStep.WELCOME, state.resumeStep)
        assertTrue(state.isGuest)
        assertFalse(state.isCompleted)
        assertEquals(OnboardingPermissionState.NOT_DETERMINED, state.contactsPermission)
        assertTrue(state.topFiveSelections.isEmpty())
    }

    @Test
    fun completeStep_advancesPersistedResumeStepInOrder() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        repository.completeStep(OnboardingStep.WELCOME)
        assertEquals(OnboardingStep.PERMISSION, repository.observeState().first().resumeStep)

        repository.completeStep(OnboardingStep.PERMISSION)
        assertEquals(OnboardingStep.GUEST_LOGIN, repository.observeState().first().resumeStep)

        repository.completeStep(OnboardingStep.GUEST_LOGIN)
        assertEquals(OnboardingStep.TOP_FIVE, repository.observeState().first().resumeStep)
    }

    @Test
    fun guestLoginCompletion_marksSessionAsGuest() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        repository.completeStep(OnboardingStep.GUEST_LOGIN)

        val state = repository.observeState().first()
        assertTrue(state.isGuest, "تخطي الدخول في المرحلة 1 = المتابعة كضيف (isGuest = true)")
    }

    @Test
    fun lastStepCompletion_marksOnboardingCompletedAndClearsResume() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        repository.completeStep(OnboardingStep.ORGANIZING)

        val state = repository.observeState().first()
        assertTrue(state.isCompleted)
        assertNull(state.resumeStep)
    }

    @Test
    fun permissionResult_isPersisted() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        repository.setContactsPermission(OnboardingPermissionState.DENIED_FOREVER)
        assertEquals(
            OnboardingPermissionState.DENIED_FOREVER,
            repository.observeState().first().contactsPermission
        )

        repository.setContactsPermission(OnboardingPermissionState.SKIPPED)
        assertEquals(
            OnboardingPermissionState.SKIPPED,
            repository.observeState().first().contactsPermission
        )
    }

    @Test
    fun topFiveSelections_persistWithWeeklyFrequencyAndCappedAtFive() = runTest {
        val repository = OnboardingRepositoryImpl(SilaPreferencesDataSource())

        val selections = listOf(
            TopFiveSelection("c1", "أحمد", Relation.BROTHER),
            TopFiveSelection("c2", "منى", Relation.AUNT_MATERNAL),
            TopFiveSelection("c3", "زياد", Relation.SON),
            TopFiveSelection("c4", "سارة", Relation.IN_LAW),
            TopFiveSelection("c5", "خالد", Relation.COUSIN_PATERNAL)
        )
        repository.saveTopFiveSelections(selections)

        val saved = repository.observeState().first().topFiveSelections
        assertEquals(5, saved.size)
        assertEquals(Relation.BROTHER, saved.first().relation)
        assertEquals(ArhamFrequency.WEEKLY, saved.first().frequency)

        // الحد الأقصى 5: الاختيار السادس يُقص ولا يرمي.
        repository.saveTopFiveSelections(selections + TopFiveSelection("c6", "اختر", Relation.CUSTOM))
        assertEquals(5, repository.observeState().first().topFiveSelections.size)

        // إفراغ الاختيارات (تخطي بعد اختيار) يرسل قائمة فارغة.
        repository.saveTopFiveSelections(emptyList())
        assertTrue(repository.observeState().first().topFiveSelections.isEmpty())
    }

    @Test
    fun completeOnboarding_setsGuestAndSurvivesReopenOfSameStorage() = runTest {
        val filePath = "build/tmp_onboarding_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val repository = OnboardingRepositoryImpl(
            SilaPreferencesDataSource(storageFilePath = filePath)
        )
        val selections = listOf(TopFiveSelection("sys1", "والدتي", Relation.MOTHER))
        repository.saveTopFiveSelections(selections)
        repository.completeOnboarding()

        // إعادة فتح نفس مسار التخزين (يعود لعملية جديدة) — الاستئناف بعد الاكتمال لا يظهر التهيئة.
        val reopened = OnboardingRepositoryImpl(
            SilaPreferencesDataSource(storageFilePath = filePath)
        )
        val state = reopened.observeState().first()
        assertTrue(state.isCompleted)
        assertTrue(state.isGuest)
        assertNull(state.resumeStep)
        assertEquals(selections, state.topFiveSelections)
    }

    @Test
    fun resumeStep_persistedBeforeCompletion_survivesReopenOfSameStorage() = runTest {
        val filePath = "build/tmp_onboarding_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val repository = OnboardingRepositoryImpl(
            SilaPreferencesDataSource(storageFilePath = filePath)
        )
        repository.completeStep(OnboardingStep.WELCOME)
        repository.completeStep(OnboardingStep.PERMISSION)
        repository.setContactsPermission(OnboardingPermissionState.LIMITED)

        val reopened = OnboardingRepositoryImpl(
            SilaPreferencesDataSource(storageFilePath = filePath)
        )
        val state = reopened.observeState().first()
        assertEquals(OnboardingStep.GUEST_LOGIN, state.resumeStep)
        assertEquals(OnboardingPermissionState.LIMITED, state.contactsPermission)
        assertFalse(state.isCompleted)
    }
}
