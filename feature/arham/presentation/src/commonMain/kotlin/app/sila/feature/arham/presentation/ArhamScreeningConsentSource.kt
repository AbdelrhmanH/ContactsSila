package app.sila.feature.arham.presentation

/**
 * بوابة موافقة فحص المكالمات (T8-11 · AC-4 · Issue #29) — يوفرها المنصة عبر Koin:
 * Android يجيب من `ScreeningRoleController.isRoleHeld()` (خالية قبل API 29 —
 * الفحص غير متاح إطلاقاً)، وiOS يعيد `null` دائماً (مراقبة عمر التطبيق فقط —
 * AC-6 بلا موافقة نظام). `null` ⇒ إخفاء بطاقة الموافقة كلياً (§O.5 هادئ).
 */
fun interface ArhamScreeningConsentSource {

    /** `true` الدور محمول، `false` يلزم موافقة، `null` المنصة لا تنطبق (إخفاء). */
    fun isRoleHeld(): Boolean?
}
