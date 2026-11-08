package app.sila.feature.settings.domain

class SetDuplicateScanUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) =
        repository.setDuplicateScanEnabled(enabled)
}
