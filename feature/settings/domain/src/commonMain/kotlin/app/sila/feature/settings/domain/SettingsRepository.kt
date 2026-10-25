package app.sila.feature.settings.domain

import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.DefaultTab
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع الإعدادات (طبقة Domain داخل :feature:settings:domain — DIP).
 */
interface SettingsRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: AppThemeMode)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setDefaultTab(tab: DefaultTab)
    suspend fun setArhamNotificationsEnabled(enabled: Boolean)
    suspend fun setSmartFilterEnabled(enabled: Boolean)
}
