package app.sila.arham.notification

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import org.koin.mp.KoinPlatformTools

/**
 * منطق إطلاق وإعادة جدولة تذكير أرحام اليومي (T8-08 · §M · D2 المصادَق) — نهايةُ
 * المخطط المشترك: المستقبِل يستدعي [onDailyAlarm]، والمهيئ (SilaApplication) يستدعي
 * [rescheduleFromNow]، وكلاهما يمرّ عبر القرار المحتّي في `PlanArhamDailyNotificationUseCase`.
 *
 * **السياسات:**
 * - تعطيل الميزة (تفضيل) يمنع كل شيء — لا إشعار ولا جدولة (لا موعد بلا محتوى مسموح).
 * - إذن الإشعارات مرفوض وقت التشغيل ⇒ لا إشعار، **ومع ذلك تُعاد الجدولة** (الإذن قد
 *   يُمنح لاحقاً — والجدولة بلا تكلفة مستخدم). التفضيل يحكم الجدولة، والمنصة تحكم الظهور.
 * - بعد كل إطلاق تُعاد الجدولة للموعد التالي دائماً — ميزانية ≤ 1/يوم (§M) بلا حلقات.
 * - كل إخفاق (Koin غير بدَأ/Room/إرسال) يُسجَّل بنوعٍ حصراً — بلا PII ولا انهيار (§O.5) —
 *   والموعد المفقود يُعاد حسابه عند أقرب إيقاظ لاحق (D2).
 */
object ArhamReminderDispatch {

    /** إجراء الإنذار اليومي الصريح — مرشح المستقبِل وحكم تجاهل الأفعال الأجنبية. */
    const val ACTION_DAILY_REMINDER = "app.sila.arham.action.DAILY_REMINDER"

    /**
     * مسار الإنذار اليومي (المستقبِل): يفصل Koin بفشلٍ هادئ (قد لا يكون بدَأ في عملية
     * غريبة — §O.5)، وإلا يُطلق التحديث الكامل.
     */
    suspend fun onDailyAlarm(context: Context) {
        val koin = resolveArhamKoin() ?: run {
            Log.w(TAG, "Koin unavailable at daily alarm — skipped; rescheduled at next process start")
            return
        }
        fireDailyReminder(
            context = context.applicationContext,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
        )
    }

    /**
     * إطلاق التحديث اليومي: قراءة التفضيلات → تعطيل؟ انتهى → خطة (موعد + محتوى) →
     * ضمان القناة → إشعار إن وُجد محتوى وأذِن الإشعارات → **إعادة جدولة دائماً**.
     */
    suspend fun fireDailyReminder(
        context: Context,
        preferences: UserPreferencesDataSource,
        planner: PlanArhamDailyNotificationUseCase,
        scheduler: ArhamAlarmScheduler,
        notificationsEnabled: Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    ) {
        val prefs = preferences.preferences.first()
        if (!prefs.arhamNotificationsEnabled) return
        val plan = planner.plan(prefs)
        ArhamNotificationChannel.ensure(context)
        val content = plan.content
        if (content != null && notificationsEnabled) {
            ArhamNotificationPoster.post(context = context, content = content)
        } else if (content != null) {
            Log.i(TAG, "Arham reminder suppressed: notifications not permitted; rescheduling only")
        }
        scheduler.scheduleAt(plan.fireAtMillis)
    }

    /**
     * إعادة جدولة من اللحظة الحالية (تشغيل عملية/إقلاع/**تغيير وقت**/«ذكّرني غداً»):
     * الميزة مفعّلة ⇒ خطة جديدة من `now` وتُجدول؛ معطّلة ⇒ لا جدولة إطلاقاً.
     *
     * بلا `Context` — مسار الجدولة لا يلمس المنصة (قراءة تفضيلات + خطة + جدولة في
     * العقد المشترك [ArhamAlarmScheduler])؛ هذا جعل الالتفاف عليه من مسار التفضيلات
     * الممكَن عبر منفذ إعادة الجدولة (S2-13 · تذكرة #71 · ADR-0016).
     */
    suspend fun rescheduleFromNow(
        preferences: UserPreferencesDataSource,
        planner: PlanArhamDailyNotificationUseCase,
        scheduler: ArhamAlarmScheduler,
    ) {
        val prefs = preferences.preferences.first()
        if (!prefs.arhamNotificationsEnabled) return
        val plan = planner.plan(prefs)
        scheduler.scheduleAt(plan.fireAtMillis)
    }
}

/**
 * فصل Koin بفشلٍ هادئ للمستقبِلات (نمط §O.5): الحاوية قد لا تكون بدَأت في عملية
 * الاستقبال — الإخفاق يُسجَّل بنوعٍ حصراً ويعاد العمل عند أقرب إيقاظ لاحق.
 */
internal fun resolveArhamKoin(): Koin? =
    runCatching { KoinPlatformTools.defaultContext().get() }.getOrNull()

private const val TAG = "ArhamReminderDispatch"
