package app.sila.arham.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * عقد جدولة تذكير أرحام اليومي (T8-08 · D2 المصادَق): نقطةُ الالتقاء الوحيدة بين المنطق
 * المشترك وAlarmManager على أندرويد — كل مَن يريد (إعادة) جدولة الإطلاق اليومي يستدعي
 * `scheduleAt` ولا يعرف آلية المنصة.
 *
 * **D2 — بلا إنذارات دقيقة:** التنفيذ يستخدم `setAndAllowWhileIdle` غير الدقيقة حصراً —
 * لا صلاحية `SCHEDULE_EXACT_ALARM` ولا وعداً بوقتٍ محدد (النسخة تذكر ذلك للمستخدم).
 * الموعد المفقود (Doze عميق/إطفاء) يُعاد حسابه عند أقرب إيقاظ لاحق (تشغيل عملية/
 * إقلاع/تغيير وقت — D2 §8: الجدولة مشتقة من Room والتفضيلات عند كل إطلاق).
 */
interface ArhamAlarmScheduler {

    /**
     * يجدول الإطلاق التالي عند لحظة معيّنة (epoch millis). الاستدعاءات المتتابعة
     * **تستبدل** الموعد السابق ولا تتراكم فوقه (ميزانية ≤ 1/يوم — §M).
     */
    fun scheduleAt(fireAtMillis: Long)
}

/**
 * تنفيذ أندرويد فوق [AlarmManager]: PendingIntent واحد ثابت (رمز طلب ثابت + إجراء
 * صريح للمستقبِل) بعلمي `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` — الإعادة الجدولة
 * تستبدل الإنذار السابق داخل النظام نفسها (لا تراكم ميزانية — خطر DeepSeek 1 مُحسوم).
 */
internal class AndroidAlarmScheduler(private val context: Context) : ArhamAlarmScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ArhamReminderReceiver::class.java)
                .setAction(ArhamReminderDispatch.ACTION_DAILY_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun scheduleAt(fireAtMillis: Long) {
        // D2: غير دقيقة عمداً — بلا `SCHEDULE_EXACT_ALARM`؛ RTC_WAKEUP يوقظ الجهاز
        // ضمن قيود Doze (`setAndAllowWhileIdle` يسمح بإيقاظ واحد على الأقل لكل نافذة).
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            fireAtMillis,
            pendingIntent,
        )
    }

    private companion object {
        /** رمز طلب ثابت — مفتاح الاستبدال حصراً (لا معنى عشوائياً). */
        const val REQUEST_CODE = 2001
    }
}

/**
 * «منصة غائبة» لمضيف JVM حصراً (نمط `HostAbsentPlatformSource` — ADR-0010 §5): حين
 * لا يُسجَّل `android.content.Context` في الحاوية (اختبارات المنطق المشترك — لا تطبيق
 * أندرويد) تُربَط هذه الجدولة الصامتة بدل الطرح؛ على الجهاز الحقيقي يوجد Context دائماً
 * فلا يُنفَّذ هذا المسار أبداً.
 */
internal data object HostAbsentAlarmScheduler : ArhamAlarmScheduler {
    override fun scheduleAt(fireAtMillis: Long) = Unit
}
