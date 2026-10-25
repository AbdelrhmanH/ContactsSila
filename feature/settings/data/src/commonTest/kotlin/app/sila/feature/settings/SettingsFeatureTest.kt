package app.sila.feature.settings

import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.feature.settings.data.SettingsRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFeatureTest {

    @Test
    fun settingsRepository_updatesLanguageAndTheme() = runTest {
        val repo = SettingsRepositoryImpl(SilaPreferencesDataSource())
        repo.setLanguage(AppLanguage.ENGLISH)
        repo.setThemeMode(AppThemeMode.DARK)

        val prefs = repo.preferences.first()
        assertEquals(AppLanguage.ENGLISH, prefs.language)
        assertEquals(AppThemeMode.DARK, prefs.themeMode)
    }
}
