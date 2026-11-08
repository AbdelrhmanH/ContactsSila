package app.sila.feature.settings.domain

import app.sila.core.model.DashboardPeriod

class SetDashboardPeriodUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(period: DashboardPeriod) =
        repository.setDashboardPeriod(period)
}
