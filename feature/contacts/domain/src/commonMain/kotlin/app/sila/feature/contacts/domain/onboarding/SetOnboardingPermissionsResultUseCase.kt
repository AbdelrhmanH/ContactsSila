package app.sila.feature.contacts.domain.onboarding

/**
 * حالة استخدام أحادية المسؤولية: تدوين نتيجة صلاحية جهات الاتصال (SRP).
 * المنصة وحدها (Android shell / iOS shell) هي التي تطلب الصلاحية وتترجم حالاتها
 * إلى [OnboardingPermissionState]، فيبقى الدومين نظيفاً من أي واجهة منصة.
 */
class SetOnboardingPermissionsResultUseCase(
    private val repository: OnboardingRepository
) {
    suspend operator fun invoke(state: OnboardingPermissionState) = repository.setContactsPermission(state)
}
