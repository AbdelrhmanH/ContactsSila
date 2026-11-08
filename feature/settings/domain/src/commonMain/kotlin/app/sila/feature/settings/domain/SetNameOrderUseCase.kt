package app.sila.feature.settings.domain

import app.sila.core.model.NameOrder

class SetNameOrderUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(order: NameOrder) = repository.setNameOrder(order)
}
