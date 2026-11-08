package app.sila.feature.settings.domain

class SetCrashReportsUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) =
        repository.setCrashReportsEnabled(enabled)
}
