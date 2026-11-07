package app.sila.arham.notification

import platform.Foundation.NSBundle
import platform.UserNotifications.UNUserNotificationCenter

/**
 * نقطة اللمس الوحيدة الآمنة لمركز إشعارات iOS (تصحيح CI — سجل run 42180300317):
 * `UNUserNotificationCenter.currentNotificationCenter()` يرمي `NSInternalInconsistencyException`
 * قاتلة («bundleProxyForCurrentProcess is nil») داخل مضيف اختبار K/N — تنفيذية عارية بلا
 * حزمة تطبيق — وهي استثناء ObjC لا يُلتقط في Kotlin فتُسقط العملية (SIGABRT) بدل أن تُهمل
 * العملية المطلوبة. في التطبيق الحقيقي (حزمة سليمة) لا أثر للفحص.
 *
 * الفيصل: `NSBundle.mainBundle.bundleIdentifier` — قيمة غير فارغة داخل حزمة تطبيق حقيقية
 * (التطبيق أو الامتداد)، و`null` في التنفيذية العارية. `null` هنا يعني «المركز غير متاح» —
 * نمط المضيف الغائب ذاته (مرآة المصنع الصامت أندرويد T810-R5)؛ والمستهلكون صُمموا أصلاً
 * لتهمل العملية عند غياب المركز بصمتٍ بنوعٍ حصراً (§O.5 — `centerProvider: () -> …?`).
 *
 * قاعدة مراجعة: لا كتابة لـ `currentNotificationCenter()` خاماً في iosMain — عبر هذه النقطة
 * حصراً (أي لمس خام جديد هو أرضية ملغومة تحت مضيف الاختبار).
 */
internal fun iosNotificationCenterIfPresent(): UNUserNotificationCenter? =
    if (NSBundle.mainBundle.bundleIdentifier == null) null
    else UNUserNotificationCenter.currentNotificationCenter()
