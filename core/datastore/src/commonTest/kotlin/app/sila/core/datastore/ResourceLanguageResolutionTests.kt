package app.sila.core.datastore

import app.sila.core.model.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبارات انحدار عيب DEF-02 (تذكرة #97 — حالة اللغة غير المحفوظة).
 *
 * الجذر الموثق (جلسة 2026-11-12 المسائية — تثبيت جديد على محاكي بنظام إنجليزي):
 * عندما لا يوجد مفتاح لغة في DataStore (تثبيت جديد) كان الحسم يقبع على الافتراضي
 * `AppLanguage.ARABIC` كأنه اختيارَ مستخدم، فانطلقت عليه شريط التنقل والسمة
 * (`SilaTheme(isRtl)`) والنصوص الصلبة في `App.kt` بينما تتحول موارد Compose
 * (`composeResources/values-en`) إلى لغة النظام — واجهة مختلطة من الإطار الأول.
 *
 * الإصلاح: الحسم في مصدر التفضيلات نفسه — لغة غير محفوظة تُحسم من رمز لغة المنصة
 * التي ستحسم ضدها الموارد فعلياً (per-app locale إن وُجد وإلا لغة النظام)، فتتطابق
 * كل المصادر (الشريط، السمة، الموارد) من الإطار الأول، ويبقى الاختيار الصريح
 * للمستخدم (مسار `AppLocalePort` الواحد) أرقى من الحسم.
 *
 * الاختبارات على المسار القرصي حصراً — مسار الذاكرة (`_memoryState`) تهيئة اختبارية
 * لا يمر بـ `toUserPreferences` ولا يُستخدم في الإنتاج (KoinModule يمرر مساراً دائماً).
 */
class ResourceLanguageResolutionTests {

    @Test
    fun unstoredLanguageFollowsEnglishSystemLocale() = runTest {
        // تثبيت جديد على نظام إنجليزي: لا مفتاح لغة، والمنصة ستحسم الموارد إنجليزياً
        // (values-en) — فيجب أن يتبعها حسم الشريط والسمة (ENGLISH) لا الافتراضي العربي.
        val filePath = "build/tmp_locale_res_en_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val dataSource = SilaPreferencesDataSource(
            storageFilePath = filePath,
            resourceLanguageCodeProvider = { "en" }
        )

        assertEquals(AppLanguage.ENGLISH, dataSource.preferences.first().language)
    }

    @Test
    fun unstoredLanguageFollowsArabicSystemLocale() = runTest {
        // تثبيت جديد على نظام عربي: الحسم عربي (الموارد الافتراضية values/ عربية أصلاً).
        val filePath = "build/tmp_locale_res_ar_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val dataSource = SilaPreferencesDataSource(
            storageFilePath = filePath,
            resourceLanguageCodeProvider = { "ar" }
        )

        assertEquals(AppLanguage.ARABIC, dataSource.preferences.first().language)
    }

    @Test
    fun unstoredLanguageUnmappedSystemCodeFallsBackToDefaultResourceLanguage() = runTest {
        // لغة نظام غير مدعومة (فرنسي مثلاً): موارد Compose تتحول إلى values الافتراضية
        // (العربية) لأن لا يوجد values-fr — فيجب أن يتبعها الحسم فلا واجهة مختلطة.
        val filePath = "build/tmp_locale_res_fr_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val dataSource = SilaPreferencesDataSource(
            storageFilePath = filePath,
            resourceLanguageCodeProvider = { "fr" }
        )

        assertEquals(AppLanguage.ARABIC, dataSource.preferences.first().language)
    }

    @Test
    fun unstoredLanguageNullPlatformCodeFallsBackToInitialDefault() = runTest {
        // منبع رمز المنصة عاجز عن الإجابة (تعثر قراءة المنصة): يعود الحسم إلى الافتراضي
        // الابتدائي (العربية = لغة الموارد الافتراضية) — سلوك ما قبل الإصلاح كشبكة أمان.
        val filePath = "build/tmp_locale_res_null_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val dataSource = SilaPreferencesDataSource(
            storageFilePath = filePath,
            resourceLanguageCodeProvider = { null }
        )

        assertEquals(AppLanguage.ARABIC, dataSource.preferences.first().language)
    }

    @Test
    fun storedLanguageOverridesPlatformResolution() = runTest {
        // الاختيار الصريح للمستخدم (المكتوب عبر مسار AppLocalePort الواحد — تذكرة #97)
        // يفوز على حسم لغة المنصة: كتابة ENGLISH ثم القراءة على منبع منصة عربي.
        val filePath = "build/tmp_locale_res_stored_${Random.nextInt(100_000, 999_999)}.preferences_pb"
        val dataSource = SilaPreferencesDataSource(
            storageFilePath = filePath,
            resourceLanguageCodeProvider = { "ar" }
        )
        dataSource.setLanguage(AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, dataSource.preferences.first().language)
    }

    @Test
    fun resolveResourceLanguageMapsEnglishCodeToEnglish() {
        assertEquals(AppLanguage.ENGLISH, resolveResourceLanguage("en"))
        assertEquals(AppLanguage.ENGLISH, resolveResourceLanguage("EN"))
    }

    @Test
    fun resolveResourceLanguageMapsAnyOtherCodeToArabicDefault() {
        assertEquals(AppLanguage.ARABIC, resolveResourceLanguage("ar"))
        assertEquals(AppLanguage.ARABIC, resolveResourceLanguage("fr"))
        assertEquals(AppLanguage.ARABIC, resolveResourceLanguage("ar-EG".take(2)))
    }
}
