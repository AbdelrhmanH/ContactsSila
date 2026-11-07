package app.sila.feature.arham.presentation

/**
 * الآثار المنصوية الوحيدة التي تُصعَّد من شاشات أرحام إلى غلاف المضيف (T8-11 · Issue #29)
 * — نفس نمط [app.sila.feature.contacts.presentation] المُجمَّع في `ContactsNavEffect`:
 * الواجهة المشتركة لا تعرف المنصة، والمضيف (androidApp) ينفّذ. الاتصال يفتح الطلب
 * الهاتفي، وطلب الدور (AC-4) يطلق نية `ROLE_CALL_SCREENING` المنصوية.
 */
sealed interface ArhamPlatformEffect {

    /** فتح طلب الاتصال بالرقم E.164 — من زر القائمة أو ورقة عقدة الشجرة. */
    data class OpenDialer(val phone: String) : ArhamPlatformEffect

    /** إطلاق حوار منح دور فرز المكالمات (AC-4) — النتيجة تعود عبر [ArhamIntent.RefreshScreeningConsent]. */
    data object RequestScreeningRole : ArhamPlatformEffect
}
