package app.sila.core.datastore

import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPreferencesDataSourceTest {

    @Test
    fun defaultPreferences_areArabicRtlAndLightMode() = runTest {
        val dataSource = SilaPreferencesDataSource()
        val prefs = dataSource.preferences.first()
        assertEquals(AppLanguage.ARABIC, prefs.language)
        assertTrue(prefs.language.isRtl)
        assertEquals(AppThemeMode.LIGHT, prefs.themeMode)
    }

    @Test
    fun updatingLanguageAndTheme_emitsUpdatedPreferences() = runTest {
        val dataSource = SilaPreferencesDataSource()
        dataSource.setLanguage(AppLanguage.ENGLISH)
        dataSource.setThemeMode(AppThemeMode.DARK)

        val prefs = dataSource.preferences.first()
        assertEquals(AppLanguage.ENGLISH, prefs.language)
        assertFalse(prefs.language.isRtl)
        assertEquals(AppThemeMode.DARK, prefs.themeMode)
    }

    @Test
    fun diskBackedDataStore_persistsPreferencesAndReusesSameInstanceOnReopen() = runTest {
        val filePath = "build/tmp_prefs_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val ds1 = SilaPreferencesDataSource(storageFilePath = filePath)
        ds1.setLanguage(AppLanguage.ENGLISH)
        ds1.setThemeMode(AppThemeMode.DARK)

        // فتح مثيل ثانٍ بنفس المسار لا يرمي IllegalStateException ويقرأ نفس القيم المحفوظة على القرص
        val ds2 = SilaPreferencesDataSource(storageFilePath = filePath)
        val loaded = ds2.preferences.first()
        assertEquals(AppLanguage.ENGLISH, loaded.language)
        assertEquals(AppThemeMode.DARK, loaded.themeMode)
    }
}
