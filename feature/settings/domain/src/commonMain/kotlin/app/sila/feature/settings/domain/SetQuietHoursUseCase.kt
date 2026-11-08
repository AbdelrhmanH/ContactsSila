package app.sila.feature.settings.domain

class SetQuietHoursUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(startHour: Int, endHour: Int) =
        repository.setQuietHours(startHour, endHour)
}
