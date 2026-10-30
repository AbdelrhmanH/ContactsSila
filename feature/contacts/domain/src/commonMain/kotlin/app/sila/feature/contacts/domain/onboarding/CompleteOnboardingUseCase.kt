package app.sila.feature.contacts.domain.onboarding

/**
 * حالة استخدام أحادية المسؤولية: إنهاء التهيئة كاملة (SRP).
 * تضمن `onboardingCompleted = true` و `isGuest = true` (المرحلة 1 ضيف فقط)
 * وعدم تكرار التهيئة مطلقاً بعد الإكمال، مع صفر كتابات سحابية.
 */
class CompleteOnboardingUseCase(
    private val repository: OnboardingRepository
) {
    suspend operator fun invoke() = repository.completeOnboarding()
}
