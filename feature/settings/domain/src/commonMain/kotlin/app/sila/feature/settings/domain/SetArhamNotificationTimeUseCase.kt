package app.sila.feature.settings.domain

class SetArhamNotificationTimeUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(hour: Int, minute: Int) =
        repository.setArhamNotificationTime(hour, minute)
}
