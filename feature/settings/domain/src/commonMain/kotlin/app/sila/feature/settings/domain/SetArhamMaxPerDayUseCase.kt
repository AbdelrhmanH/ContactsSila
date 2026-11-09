package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: سقف إشعارات أرحام اليومي (SRP).
 */
class SetArhamMaxPerDayUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(max: Int) = repository.setArhamMaxPerDay(max)
}
