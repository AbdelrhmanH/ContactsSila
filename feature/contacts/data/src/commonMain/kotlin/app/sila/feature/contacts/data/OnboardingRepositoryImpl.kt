package app.sila.feature.contacts.data

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.domain.onboarding.OnboardingRepository
import app.sila.feature.contacts.domain.onboarding.OnboardingState
import app.sila.feature.contacts.domain.onboarding.OnboardingStep
import app.sila.feature.contacts.domain.onboarding.nextStep
import app.sila.core.model.TopFiveSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * تنفيذ مستودع التهيئة الأولى (:feature:contacts:data).
 * يعتمد حصرياً على DataStore التفضيلات المحلية (UserPreferencesDataSource) —
 * Single Source of Truth بدون أي اتصال شبكي أو هوية حساب (المرحلة 1 ضيف فقط).
 */
class OnboardingRepositoryImpl(
    private val dataSource: UserPreferencesDataSource
) : OnboardingRepository {

    override fun observeState(): Flow<OnboardingState> = dataSource.preferences.map { prefs ->
        OnboardingState(
            isCompleted = prefs.onboardingCompleted,
            isGuest = prefs.isGuest,
            resumeStep = if (prefs.onboardingCompleted) {
                null
            } else {
                prefs.onboardingResumeStep
                    ?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
                    ?: OnboardingStep.WELCOME
            },
            contactsPermission = prefs.onboardingContactsPermission
                ?.let { runCatching { OnboardingPermissionState.valueOf(it) }.getOrNull() }
                ?: OnboardingPermissionState.NOT_DETERMINED,
            topFiveSelections = prefs.onboardingTopFiveSelections,
            language = prefs.language
        )
    }

    override suspend fun completeStep(step: OnboardingStep) {
        // تخطي شاشة الدخول في المرحلة 1 يعني المتابعة كضيف (تخطي الآن = لا حساب).
        if (step == OnboardingStep.GUEST_LOGIN) {
            dataSource.setIsGuest(true)
        }
        val next = step.nextStep()
        if (next == null) {
            completeOnboarding()
        } else {
            dataSource.setOnboardingResumeStep(next.name)
        }
    }

    override suspend fun setContactsPermission(state: OnboardingPermissionState) {
        dataSource.setOnboardingContactsPermission(state.name)
    }

    override suspend fun saveTopFiveSelections(selections: List<TopFiveSelection>) {
        // الحد الأقصى خمسة أشخاص كما في مواصفات الميزة (حماية مزدوجة مع الواجهة).
        dataSource.setOnboardingTopFiveSelections(selections.take(MAX_TOP_FIVE_SELECTIONS))
    }

    override suspend fun completeOnboarding() {
        dataSource.setIsGuest(true)
        dataSource.setOnboardingResumeStep(null)
        dataSource.setOnboardingCompleted(true)
    }

    private companion object {
        const val MAX_TOP_FIVE_SELECTIONS = 5
    }
}
