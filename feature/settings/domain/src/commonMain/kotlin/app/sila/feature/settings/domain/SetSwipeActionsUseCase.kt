package app.sila.feature.settings.domain

import app.sila.core.model.ContactSwipeAction

class SetSwipeActionsUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(start: ContactSwipeAction, end: ContactSwipeAction) =
        repository.setSwipeActions(start, end)
}
