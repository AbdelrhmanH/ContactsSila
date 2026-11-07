package app.sila.arham.emergency

import platform.Foundation.NSBundle

/**
 * نصوص إشعار الطوارئ على iOS (T8-13) — مرآة [app.sila.arham.notification.IosArhamNotificationStrings]:
 * تُقرأ من `Localizable.strings` الخاصة بحزمة التطبيق (en/ar بنسق 255=255 المكفول) بدل تثبيت نصوص في الكود.
 * المفاتيح مرآة حرفية لأندرويد (`arham_emergency_notification_*`) لضمان تكافؤ النسختين (T808-R4).
 */
interface IosArhamEmergencyStrings {

    /** عنوان إشعار "حلقة الطوارئ نشطة". */
    fun activeTitle(): String

    /** متن الإشعار مع اسم الظهور (يُنسّق هنا بدل الواجهة — مرآة `formatDigestBody`). */
    fun activeBody(displayName: String): String

    /** البديل الذي يظهر حين يكون اسم الظهور فارغاً. */
    fun targetUnknown(): String
}

/**
 * التنفيذ الحقيقي عبر `NSBundle.mainBundle.localizedString(forKey:value:table:)` — مرآة
 * [app.sila.arham.notification.IosBundleArhamNotificationStrings] (القيمة الافتراضية = المفتاح
 * لتظل الأعطال ملاحظة في التطوير بدل نص فارغ صامت).
 */
class IosBundleArhamEmergencyStrings(private val bundle: NSBundle = NSBundle.mainBundle) :
    IosArhamEmergencyStrings {

    private fun localized(key: String): String =
        bundle.localizedStringForKey(key = key, value = key, table = null)

    override fun activeTitle(): String = localized(KEY_ACTIVE_TITLE)

    override fun activeBody(displayName: String): String =
        localized(KEY_ACTIVE_BODY).replace(PLACEHOLDER_DISPLAY_NAME, displayName)

    override fun targetUnknown(): String = localized(KEY_TARGET_UNKNOWN)

    private companion object {
        const val KEY_ACTIVE_TITLE = "arham_emergency_notification_title"
        const val KEY_ACTIVE_BODY = "arham_emergency_notification_body"
        const val KEY_TARGET_UNKNOWN = "arham_emergency_notification_target_unknown"

        /** iOS: `%1$@` للنصوص (بدل `%1$s` في أندرويد) — ترجمة الموضع محفوظة للنسختين. */
        const val PLACEHOLDER_DISPLAY_NAME = "%1$@"
    }
}
