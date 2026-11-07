package app.sila.arham.notification

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlinx.coroutines.flow.first

/**
 * عقدُ الجدولة والإلغاء الذي يستهلكه المسار المنصّي (T8-12) — يُنفّذه
 * [IosArhamNotificationScheduler]؛ فصله عن `ArhamAlarmScheduler` المشترك لأن الإلغاء
 * المباشر مسار iOS حصراً (الزنّاد المتكرر بلا مستقبِل إعادة تقييم) — وحقنه يسمح
 * بتزييف الجدولة في اختبارات قرار الإزاحة بلا مركز إشعارات.
 */
interface IosArhamReminderScheduling {

    /** جدولة الإطلاق القادم (استبدال حصراً — ميزانية ≤ 1/يوم). */
    fun scheduleAt(fireAtMillis: Long)

    /** إلغاء الطلب المعلّق (مسار التعطيل/غياب المحتوى). */
    fun cancelPending()
}

/**
 * منطق إعادة جدولة تذكير أرحام اليومي على iOS (T8-12 · §M · D2 المصادَق) — نهايةُ
 * المخطط المشترك المنصّية، مرآة `ArhamReminderDispatch` أندرويد: المهيئ
 * (IosKoinBootstrap) يستهلك [rescheduleFromNow] عند كل إطلاق عملية على نطاق عمر
 * التطبيق، وفعل «ذكّرني غداً» في مندوب الإشعارات يستهلكها كذلك — والقرار كله عبر
 * [PlanArhamDailyNotificationUseCase] (لا قرار منصّياً مكرر — O-7).
 *
 * **فارق معلوم عن أندرويد (وثائقي لا مخلّ):** ميزةٌ معطَّلة تلغي الطلب المعلّق مباشرةً —
 * الزنّاد المتكرر على iOS لا يملك مستقبِلَ إطلاقٍ يعيد التقييم، فتركُه يتائح بعد التعطيل
 * يبقي إشعاراً يومياً بمحتوى متصاعم؛ الأمانة تقتضي الإلغاء الفوري هنا.
 */
object IosArhamReminderDispatch {

    /**
     * إعادة جدولة من اللحظة الحالية (تشغيل عملية/تغيير تفضيل/«ذكّرني غداً»): الميزة
     * مفعّلة ⇒ خطة جديدة من `now` وتُجدول؛ معطّلة ⇒ إلغاء الطلب المعلّق كلياً.
     * المعلّقة مطابقةٌ لمرآة أندرويد — يستهلكها نطاق غير رئيسي حصراً (المهيئ/المندوب).
     */
    suspend fun rescheduleFromNow(
        preferences: UserPreferencesDataSource,
        planner: PlanArhamDailyNotificationUseCase,
        scheduler: IosArhamReminderScheduling,
    ) {
        val prefs = preferences.preferences.first()
        if (!prefs.arhamNotificationsEnabled) {
            scheduler.cancelPending()
            return
        }
        val plan = planner.plan(prefs)
        scheduler.scheduleAt(plan.fireAtMillis)
    }
}
