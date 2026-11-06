package app.sila.arham.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * قناة إشعارات أرحام (T8-08 · §M): تُنشأ مرةً واحدة بشكل idempotent —
 * `getNotificationChannel` أولاً، والإنشاء ثانياً لا يعبث بقناة قائمة (النظام يتجاهل
 * تعديل الإهمال إن تغيّر). أهمية `DEFAULT` حصراً: لا نافذة منبثقة إلحاحية فوق
 * المستخدم — التذكير دعوةٌ لصلة الرحم لا استعجال (بلا صوت مفرط).
 *
 * minSdk 26 — `NotificationChannel` بلا فحص إصدار.
 */
object ArhamNotificationChannel {

    /** معرّف القناة الثابت — مرجع البناء والمسح معاً. */
    const val CHANNEL_ID = "arham_reminders"

    /** يضمن وجود القناة قبل أي إشعار — آمن للاستدعاء المتكرر (تشغيل/إطلاق/إرسال). */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(app.sila.sharedLogic.R.string.arham_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }
}
