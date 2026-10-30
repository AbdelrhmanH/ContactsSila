package app.sila.feature.contacts.domain.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة حالة التهيئة الأولى (SRP).
 */
class ObserveOnboardingStateUseCase(
    private val repository: OnboardingRepository
) {
    operator fun invoke(): Flow<OnboardingState> = repository.observeState()
}
