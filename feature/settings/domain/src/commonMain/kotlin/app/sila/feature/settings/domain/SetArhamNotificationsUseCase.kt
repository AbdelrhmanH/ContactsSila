package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تفعيل أو تعطيل إشعارات أرحام (SRP).
 */
class SetArhamNotificationsUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setArhamNotificationsEnabled(enabled)
}
