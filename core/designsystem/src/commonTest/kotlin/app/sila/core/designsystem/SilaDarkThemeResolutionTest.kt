package app.sila.core.designsystem

import app.sila.core.model.AppThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * عقد قرار الوضع الداكن الفعّال (DEF-03 · PRD §12 «فاتح / داكن / حسب النظام»):
 *
 * العطل المرصود: قرار الواجهة كان `themeMode == AppThemeMode.DARK` حصراً فيموت وضع
 * «حسب النظام» — وهو افتراضي `themeMode` في `UserPreferences` — فيُعرض التطبيق
 * فاتحاً دائماً حتى مع نظام داكن، في كلا موضعَي القرار (قشرة [App] وحوار اختيار
 * الرقم على Android).
 */
class SilaDarkThemeResolutionTest {

    @Test
    fun darkModeForcesDarkRegardlessOfSystemState() {
        assertTrue(resolvesSilaDarkTheme(AppThemeMode.DARK, systemInDarkTheme = false))
        assertTrue(resolvesSilaDarkTheme(AppThemeMode.DARK, systemInDarkTheme = true))
    }

    @Test
    fun lightModeForcesLightRegardlessOfSystemState() {
        assertFalse(resolvesSilaDarkTheme(AppThemeMode.LIGHT, systemInDarkTheme = true))
        assertFalse(resolvesSilaDarkTheme(AppThemeMode.LIGHT, systemInDarkTheme = false))
    }

    @Test
    fun systemModeFollowsThePlatformDarkState() {
        assertTrue(resolvesSilaDarkTheme(AppThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolvesSilaDarkTheme(AppThemeMode.SYSTEM, systemInDarkTheme = false))
    }
}
