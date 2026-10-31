package app.sila.feature.contacts.domain.onboarding

/**
 * حالة استخدام أحادية المسؤولية: إعلان اكتمال خطوة من خطوات التهيئة (SRP).
 * يدوّم الخطوة التالية بحيث تستأنف التهيئة عند أول خطوة غير مكتملة بعد أي انقطاع.
 */
class CompleteOnboardingStepUseCase(
    private val repository: OnboardingRepository
) {
    suspend operator fun invoke(step: OnboardingStep) = repository.completeStep(step)
}
