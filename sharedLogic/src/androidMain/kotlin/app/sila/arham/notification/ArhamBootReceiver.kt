package app.sila.arham.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبِل استمرارية الجدولة (T8-08 · D2 §8): إقلاع الجهاز أو تغيير وقت/منطقة النظام
 * يُبطل الموعد المحسوب (بُني على الساعة المحلية) — هنا يُعاد حسابه من `now` نفسها.
 * القرار المحتّي واحد (`rescheduleFromNow`) فلا انحراف بين المسارات الثلاثة.
 */
class ArhamBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_TIME_SET,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> reschedule(context.applicationContext)
        }
    }

    private fun reschedule(appContext: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val koin = resolveArhamKoin() ?: return@runCatching
                    ArhamReminderDispatch.rescheduleFromNow(
                        context = appContext,
                        preferences = koin.get(),
                        planner = koin.get(),
                        scheduler = koin.get(),
                    )
                }.onFailure {
                    Log.w(TAG, "Reschedule after boot/time-change failed: ${it.javaClass.simpleName}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ArhamBootReceiver"

        /**
         * بثٌّ نظامي محمي بنصّه الحرفي — مُطابَق للمرشح في المانيفست حرفياً (ثابت
         * `Intent` العام لا يغطي `TIME_SET` بهذا الاسم في كل الإصدارات، والنصّ
         * الحرفي مرجع النظام الوحيد المضمون).
         */
        const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
    }
}
