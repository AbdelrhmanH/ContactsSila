package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: نصف قطر البحث الجغرافي (كم) للخدمات (SRP).
 */
class SetSearchRadiusKmUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(radiusKm: Int) = repository.setSearchRadiusKm(radiusKm)
}
