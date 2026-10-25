package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تفعيل أو تعطيل التصنيف الذكي التلقائي (SRP).
 */
class SetSmartFilterUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setSmartFilterEnabled(enabled)
}
