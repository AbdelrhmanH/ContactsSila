package app.sila.arham.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبِل أفعال الإشعار (T8-08 · §M · D1 بند 7 · إصلاح A-N08):
 * - **«ذكّرني غداً»:** إزاحةُ جدولةٍ لا حالةُ كتابة — إعادة خطة من `now` (الفتحة
 *   التالية المستقبلية) وإعادة جدولتها، ثم مسح الإشعار الحالي.
 * - **«اتصل الآن»:** أُسند إطلاقه إلى النظام مباشرةً عبر `PendingIntent.getActivity`
 *   (نمط نقرة المحتوى المثبت — A-N07) لأن البثّ عبر هذا المستقبِل ثم `startActivity`
 *   من مكوّنٍ خلفي تُجهاضه سياسة بدء-الخلفية حتى بحالة التطبيق الطازجة (`-91` على
 *   المحاكي وANFE على MIUI — جلسة 2026-11-14). يظل هذا المستقبِل يتكفل ببثّات
 *   «اتصل الآن» متبقية من تثبيتٍ أقدم (مسار احتياطي).
 * - **التنظيف:** مسح الإشعار في `finally` — يُخفى بعد تفاعل المستخدم حتى عند الرمي
 *   (كان داخل `runCatching` فيقفز مع الاستثناء فيبقى إشعاراً ميتاً في الظل).
 * كلا الفعلين يمسحان الإشعار بعد التنفيذ (ميزانية ظاهر واحد — FLAG_UPDATE_CURRENT
 * على الأفعال نفسها يستبدل ولا يتراكم).
 */
class ArhamNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runAction(context.applicationContext, action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** تنفيذ الفعل (اتصل الآن / ذكّرني غداً) — الدوران مُستخرَج ليعاد استخدامه في الاختبارات. */
    internal suspend fun runAction(appContext: Context, action: String) {
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
            }.onFailure {
                Log.w(TAG, "Notification action failed: ${it.javaClass.simpleName}")
            }
        } finally {
            // ما يحميه finally: ضمان إخفاء إشعار 2001 بعد تفاعل المستخدم على كل المسارات
            // (نجاح/فشل) — كان المسح داخل runCatching فيتخطى عند الرمي. runCatching
            // الداخلي يحفظ خلويّة العملية: استثناء من المسح نفسه لا يهبط غيرمُعالجاً
            // في نطاق coroutine المستقبِل فيسقط التطبيق.
            runCatching {
                NotificationManagerCompat.from(appContext)
                    .cancel(ArhamNotificationPoster.REMINDER_NOTIFICATION_ID)
            }.onFailure {
                Log.w(TAG, "Notification cleanup failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /** فتح التطبيق بمكوّنه الصريح عبر `createLaunchAppIntent` — بلا extras (مسار احتياطي). */
    private fun launchApp(context: Context) {
        context.startActivity(ArhamNotificationPoster.createLaunchAppIntent(context))
    }

    private companion object {
        const val TAG = "ArhamNotificationActionReceiver"
    }
}
