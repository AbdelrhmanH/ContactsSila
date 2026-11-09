package app.sila.arham.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_LAUNCHER
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبِل أفعال الإشعار (T8-08 · §M · D1 بند 7):
 * - **«اتصل الآن»:** يفتح التطبيق حصراً — بلا أرقام ولا أسماء في الـ Intent (لا PII)،
 *   وسجل المحاولات إن حدث يُكتب من داخل التطبيق عبر مسار T8-06 القياسي.
 * - **«ذكّرني غداً»:** إزاحةُ جدولةٍ لا حالةُ كتابة — إعادة خطة من `now` (الفتحة
 *   التالية المستقبلية) وإعادة جدولتها، ثم مسح الإشعار الحالي.
 * كلا الفعلين يمسحان الإشعار بعد التنفيذ (ميزانية ظاهر واحد — FLAG_UPDATE_CURRENT
 * على الأفعال نفسها يستبدل ولا يتراكم).
 */
class ArhamNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    when (action) {
                        ArhamNotificationPoster.ACTION_CALL_NOW -> launchApp(appContext)
                        ArhamNotificationPoster.ACTION_REMIND_TOMORROW -> {
                            val koin = resolveArhamKoin()
                            if (koin != null) {
                                ArhamReminderDispatch.rescheduleFromNow(
                                    preferences = koin.get(),
                                    planner = koin.get(),
                                    scheduler = koin.get(),
                                )
                            }
                        }
                    }
                    NotificationManagerCompat.from(appContext)
                        .cancel(ArhamNotificationPoster.REMINDER_NOTIFICATION_ID)
                }.onFailure {
                    Log.w(TAG, "Notification action failed: ${it.javaClass.simpleName}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** فتح التطبيق عبر MAIN/LAUNCHER على حزمة التطبيق صراحةً — بلا بيانات مرفقة. */
    private fun launchApp(context: Context) {
        context.startActivity(
            Intent(ACTION_MAIN)
                .addCategory(CATEGORY_LAUNCHER)
                .setPackage(context.packageName)
                .addFlags(FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val TAG = "ArhamNotificationActionReceiver"
    }
}
