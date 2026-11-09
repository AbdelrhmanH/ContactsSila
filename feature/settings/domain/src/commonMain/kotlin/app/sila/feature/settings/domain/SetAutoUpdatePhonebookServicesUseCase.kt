package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تشغيل/إيقاف التحديث التلقائي لدليل الخدمات (SRP).
 */
class SetAutoUpdatePhonebookServicesUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setAutoUpdatePhonebookServices(enabled)
}
