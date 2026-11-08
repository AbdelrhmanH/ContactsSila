package app.sila.core.datastore

import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
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

    @Test
    fun diskBacked_celebratedTransitions_mergeWithPersistedMapOnReopen() = runTest {
        val filePath = "build/tmp_celebrations_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val ds1 = SilaPreferencesDataSource(storageFilePath = filePath)
        ds1.markArhamTransitionCelebrated(contactId = 42L, periodKey = 202645L)

        // محاكاة إعادة تشغيل العملية: مثيل جديد بنفس الملف تبدأ مرآته الذاكرية من الافتراضي،
        // فيجب أن يُدمج احتفالها الجديد فوق الخريطة المحفوظة لا أن يمحوها.
        val ds2 = SilaPreferencesDataSource(storageFilePath = filePath)
        ds2.markArhamTransitionCelebrated(contactId = 43L, periodKey = 202646L)

        // قراءة حتمية عبر مثيل ثالث فتيح على المسار نفسه (كأنها عملية قارئة لاحقة).
        val ds3 = SilaPreferencesDataSource(storageFilePath = filePath)
        val persisted = ds3.preferences.first().celebratedArhamTransitions
        assertEquals(202645L, persisted[42L], "علامة احتفال العملية السابقة (42) لا تُفقد بعد إعادة التشغيل")
        assertEquals(202646L, persisted[43L], "احتفال العملية الحالية (43) يُحفظ")
    }

    @Test
    fun topFiveMarker_defaultsFalse_thenRaisedAndEmitted() = runTest {
        val dataSource = SilaPreferencesDataSource()

        assertFalse(dataSource.preferences.first().topFiveMaterialized, "T8-04 §F.10: الافتراضي غير مُجسَّدة")

        dataSource.markTopFiveMaterialized()
        assertTrue(dataSource.preferences.first().topFiveMaterialized, "الرفع يُبثّ في التدفق")
    }

    @Test
    fun topFiveMarker_neverResetByReSelection() = runTest {
        val dataSource = SilaPreferencesDataSource()
        dataSource.markTopFiveMaterialized()

        // §F.6/§F.10: إعادة الاختيار أثناء التهيئة تكتب القائمة المعلّقة حصراً — والعلامة لا تُصفَّر.
        dataSource.setOnboardingTopFiveSelections(
            listOf(
                TopFiveSelection(
                    systemContactId = "sys_one",
                    displayName = "العم أيمن",
                    relation = Relation.UNCLE_PATERNAL,
                ),
            ),
        )

        assertTrue(dataSource.preferences.first().topFiveMaterialized, "علامة الإنجاز لا تُعاد فتحها أبداً (one-shot §F.10)")
    }
}
