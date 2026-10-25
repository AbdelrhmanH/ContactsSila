package app.sila.feature.settings.data

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.DefaultTab
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * تنفيذ مستودع الإعدادات (طبقة Data داخل :feature:settings:data).
 */
class SettingsRepositoryImpl(
    private val dataSource: UserPreferencesDataSource
) : SettingsRepository {

    override val preferences: Flow<UserPreferences> = dataSource.preferences

    override suspend fun setThemeMode(mode: AppThemeMode) {
        dataSource.setThemeMode(mode)
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataSource.setLanguage(language)
    }

    override suspend fun setDefaultTab(tab: DefaultTab) {
        dataSource.setDefaultTab(tab)
    }

    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) {
        dataSource.setArhamNotificationsEnabled(enabled)
    }

    override suspend fun setSmartFilterEnabled(enabled: Boolean) {
        dataSource.setSmartFilterEnabled(enabled)
    }
}
