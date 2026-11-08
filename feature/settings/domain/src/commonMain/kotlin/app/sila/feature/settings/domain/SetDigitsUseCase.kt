package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits

class SetDigitsUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(digits: AppDigits) = repository.setDigits(digits)
}
