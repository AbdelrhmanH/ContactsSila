package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تعيين المدينة الافتراضية لحسابات الطقس/المواعيد (SRP).
 */
class SetDefaultCityUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(city: String?) = repository.setDefaultCity(city)
}
