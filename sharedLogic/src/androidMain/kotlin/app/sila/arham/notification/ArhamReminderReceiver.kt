package app.sila.arham.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبِل الإنذار اليومي (T8-08 · D2): يخدم الإنذار الثابت الوحيد للميزة — يرفض أي
 * إجراءً آخر صراحةً (المرشح في المانيفست بلا intent-filter، والفحص تحصين)، ويشغّل
 * الإطلاق خارج الخيط الرئيسي عبر `goAsync` (قراءة Room/التفضيلات داخل الخطة).
 * أي إخفاق داخلي (Room/قناة/إرسال) يُسجَّل بنوعٍ حصراً ولا يسقط العملية (§O.5).
 */
class ArhamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ArhamReminderDispatch.ACTION_DAILY_REMINDER) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    ArhamReminderDispatch.onDailyAlarm(context.applicationContext)
                }.onFailure {
                    Log.w(TAG, "Daily reminder dispatch failed: ${it.javaClass.simpleName}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ArhamReminderReceiver"
    }
}
