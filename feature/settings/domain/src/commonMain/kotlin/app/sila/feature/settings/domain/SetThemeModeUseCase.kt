package app.sila.feature.settings.domain

import app.sila.core.model.AppThemeMode

/**
 * حالة استخدام أحادية المسؤولية: تغيير نمط الثيم (SRP).
 */
class SetThemeModeUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(mode: AppThemeMode) = repository.setThemeMode(mode)
}
