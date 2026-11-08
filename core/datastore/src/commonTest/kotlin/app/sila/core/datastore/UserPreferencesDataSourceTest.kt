package app.sila.core.datastore

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserPreferencesDataSourceTest {

    @Test
    fun defaultPreferences_areArabicRtlAndLightMode() = runTest {
        val dataSource = SilaPreferencesDataSource()
        val prefs = dataSource.preferences.first()
        assertEquals(AppLanguage.ARABIC, prefs.language)
        assertTrue(prefs.language.isRtl)
        assertEquals(AppThemeMode.SYSTEM, prefs.themeMode)
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

    @Test
    fun t9Preferences_allDocumentedDefaults_matchSpec() = runTest {
        val dataSource = SilaPreferencesDataSource()
        val prefs = dataSource.preferences.first()

        assertEquals(AppDigits.ARABIC, prefs.digits)
        assertEquals(NameOrder.FIRST_LAST, prefs.nameOrder)
        assertEquals(DefaultTab.CONTACTS, prefs.defaultTab)
        assertTrue(prefs.visibleAccounts.isEmpty())
        assertNull(prefs.defaultSimSlot)
        assertEquals(1, prefs.arhamMaxPerDay)
        assertEquals(DashboardPeriod.WEEK, prefs.dashboardPeriod)
        assertTrue(prefs.autoTrackingEnabled)
        assertNull(prefs.defaultCity)
        assertEquals(5, prefs.searchRadiusKm)
        assertTrue(prefs.quickRatingEnabled)
        assertFalse(prefs.autoUpdatePhonebookServices)
        assertTrue(prefs.smartFilterEnabled)
        assertTrue(prefs.duplicateScanEnabled)
        assertTrue(prefs.postCallCategorizeEnabled)
        assertTrue(prefs.crashReportsEnabled)
        assertTrue(prefs.interestTags.isEmpty())
        assertTrue(prefs.categoryRingtones.isEmpty())
        assertEquals(ContactSwipeAction.CALL, prefs.swipeStartAction)
        assertEquals(ContactSwipeAction.MESSAGE, prefs.swipeEndAction)
    }

    @Test
    fun t9Preferences_updatingAllSettings_persistsAndLoadsOnDisk() = runTest {
        val filePath = "build/tmp_t9_prefs_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val ds1 = SilaPreferencesDataSource(storageFilePath = filePath)

        ds1.setDigits(AppDigits.HINDI)
        ds1.setNameOrder(NameOrder.LAST_FIRST)
        ds1.setDefaultTab(DefaultTab.ARHAM)
        ds1.setVisibleAccounts(setOf("google@gmail.com", "device"))
        ds1.setDefaultSimSlot(1)
        ds1.setArhamMaxPerDay(3)
        ds1.setDashboardPeriod(DashboardPeriod.MONTH)
        ds1.setAutoTrackingEnabled(false)
        ds1.setDefaultCity("الرياض")
        ds1.setSearchRadiusKm(15)
        ds1.setQuickRatingEnabled(false)
        ds1.setAutoUpdatePhonebookServices(true)
        ds1.setDuplicateScanEnabled(false)
        ds1.setPostCallCategorizeEnabled(false)
        ds1.setCrashReportsEnabled(false)
        ds1.setInterestTags(setOf("نجارة", "صيانة"))
        ds1.setCategoryRingtone(Category.FAMILY, "content://media/family.mp3")
        ds1.setSwipeActions(ContactSwipeAction.NONE, ContactSwipeAction.CALL)

        // قراءة من مثيل جديد على نفس الملف للتحقق من حفظ القرص
        val ds2 = SilaPreferencesDataSource(storageFilePath = filePath)
        val loaded = ds2.preferences.first()

        assertEquals(AppDigits.HINDI, loaded.digits)
        assertEquals(NameOrder.LAST_FIRST, loaded.nameOrder)
        assertEquals(DefaultTab.ARHAM, loaded.defaultTab)
        assertEquals(setOf("google@gmail.com", "device"), loaded.visibleAccounts)
        assertEquals(1, loaded.defaultSimSlot)
        assertEquals(3, loaded.arhamMaxPerDay)
        assertEquals(DashboardPeriod.MONTH, loaded.dashboardPeriod)
        assertFalse(loaded.autoTrackingEnabled)
        assertEquals("الرياض", loaded.defaultCity)
        assertEquals(15, loaded.searchRadiusKm)
        assertFalse(loaded.quickRatingEnabled)
        assertTrue(loaded.autoUpdatePhonebookServices)
        assertFalse(loaded.duplicateScanEnabled)
        assertFalse(loaded.postCallCategorizeEnabled)
        assertFalse(loaded.crashReportsEnabled)
        assertEquals(setOf("نجارة", "صيانة"), loaded.interestTags)
        assertEquals("content://media/family.mp3", loaded.categoryRingtones[Category.FAMILY])
        assertEquals(ContactSwipeAction.NONE, loaded.swipeStartAction)
        assertEquals(ContactSwipeAction.CALL, loaded.swipeEndAction)
    }

    @Test
    fun t9Preferences_categoryRingtones_individualAndBulkUpdate() = runTest {
        val dataSource = SilaPreferencesDataSource()

        dataSource.setCategoryRingtone(Category.FAMILY, "content://media/family.mp3")
        dataSource.setCategoryRingtone(Category.FRIENDS, "content://media/friends.mp3")

        var prefs = dataSource.preferences.first()
        assertEquals("content://media/family.mp3", prefs.categoryRingtones[Category.FAMILY])
        assertEquals("content://media/friends.mp3", prefs.categoryRingtones[Category.FRIENDS])

        // إزالة نغمة
        dataSource.setCategoryRingtone(Category.FAMILY, null)
        prefs = dataSource.preferences.first()
        assertNull(prefs.categoryRingtones[Category.FAMILY])
        assertEquals("content://media/friends.mp3", prefs.categoryRingtones[Category.FRIENDS])

        // تحديث جماعي
        dataSource.setCategoryRingtones(mapOf(Category.WORK to "content://media/work.mp3"))
        prefs = dataSource.preferences.first()
        assertEquals(1, prefs.categoryRingtones.size)
        assertEquals("content://media/work.mp3", prefs.categoryRingtones[Category.WORK])
    }
}
