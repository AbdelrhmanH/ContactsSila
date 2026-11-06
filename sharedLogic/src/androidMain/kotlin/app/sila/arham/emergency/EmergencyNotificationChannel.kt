package app.sila.arham.emergency

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * قناة إشعار الطوارئ الجارية (T8-10 · §K.6 · Issue #29) — `IMPORTANCE_LOW` حصراً:
 * إشعار الخدمة الأمامية الجارٍ **لا يرنّ ولا يهتزّ بنفسه** (الرنين الفعلي هو المكالمة
 * المُطلقة؛ تنبيهُ القناة يُكرّر الصوت فوق الحلقة). إضافةٌ قناة ثانية مُفصَحة عن §M
 * (قناة تذكير واحدة) — منصّة FGS تشترط قناة جارية مستقلة بلا تشويش.
 *
 * minSdk 26 ⇒ `NotificationChannel` متاح دائماً. idempotent عبر فحص `getNotificationChannel`.
 */
object EmergencyNotificationChannel {

    /** معرف قناة الطوارئ الجارية — منفصل عن قناة التذكير اليومي (T8-08). */
    const val CHANNEL_ID = "arham_emergency"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(app.sila.sharedLogic.R.string.arham_emergency_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
