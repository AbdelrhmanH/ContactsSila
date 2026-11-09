package app.sila.feature.settings.domain

import app.sila.core.model.AppLanguage

/**
 * منفذ تطبيق لغة التطبيق على المنصة (S2-14 — تذكرة #68).
 *
 * تفضيل اللغة يجب أن يقود موارد النصوص في التطبيق كله، لا شاشة الإعدادات
 * وحدها: ميزة الإعدادات تدير نصوصها بجداول Kotlin تتبع الحالة، بينما تُحمَّل
 * موارد بقية الشاشات من Compose (composeResources) وفق بيئة المنصة الفعلية.
 * على أندرويد القناة الرسمية لذلك هي per-app locales — والمندوب
 * (`AppCompatDelegate.setApplicationLocales`) يجعل نية `SettingsIntent.SetLanguage`
 * هي منبع لغة المنصة الفعلية فتتبع الموارد كلها.
 *
 * iOS: المنفذ كائن سلبي — شاشاته الأصلية يتماشى نصوصها مع `preferences.language`
 * عبر (SettingsLocalizedStrings(language:)) أصلاً، فلا per-app locales عندها.
 *
 * المسار الاستدعائي: من `SettingsViewModel` بعد نجاح كتابة التفضيل حصراً
 * (نمط ADR-0016 «فور نجاح الكتابة») — لا نداءً مباشراً من الواجهة.
 */
interface AppLocalePort {

    /**
     * يطبق اللغة على موارد المنصة. لا يصدر قراراً ولا يكتب تفضيلاً —
     * تطبيقٌ فقط (وتعثر المنصة يبقى صامتاً دون مساس بالواجهة).
     */
    suspend fun applyApplicationLocale(language: AppLanguage)
}
