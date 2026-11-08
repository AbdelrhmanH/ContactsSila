package app.sila.feature.settings.domain

class SetAutoTrackingEnabledUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) =
        repository.setAutoTrackingEnabled(enabled)
}
