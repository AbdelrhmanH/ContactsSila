package app.sila.di

/**
 * رمز لغة المنصة التي ستحسم ضدها موارد الواجهة فعلياً — per-app locale إن وُجد
 * وإلا لغة النظام (إصلاح عيب DEF-02، تذكرة #97). تنفيذات المنصات في
 * `PlatformResourceLocale.android.kt` و`PlatformResourceLocale.ios.kt`، والاستهلاك
 * في [app.sila.core.datastore.resolveResourceLanguage] عبر حقنه في منبع
 * التفضيلات. على نمط المنافذ (ADR-0014) — قناة `expect/actual` واحدة لقراءة
 * نظامية واحدة، بلا استهلاك مباشر للمنصة من الواجهة.
 */
expect fun platformResourceLanguageCode(): String?
