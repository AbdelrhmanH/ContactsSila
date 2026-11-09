package app.sila.arham.notification

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import app.sila.feature.settings.domain.ArhamReminderSchedulingPort

/**
 * غلاف iOS لمنفذ إعادة الجدولة الفورية من الإعدادات (S2-13 · تذكرة #71 · ADR-0016):
 * تفويض حرف إلى [IosArhamReminderDispatch.rescheduleFromNow] — ويحفظ فارق المنصة
 * الموثّق: تعطيل الميزة يُلغي الطلب المعلّق فوراً ([IosArhamReminderScheduling.cancelPending])
 * لأن iOS لا يملك مستقبِل إقلاع يعيد التقييم (ترك التعطيل بلا إلغاء يبقي إشعاراً يومياً
 * بعد نهايته).
 */
class IosArhamReminderSchedulingPort(
    private val preferences: UserPreferencesDataSource,
    private val planner: PlanArhamDailyNotificationUseCase,
    private val scheduling: IosArhamReminderScheduling,
) : ArhamReminderSchedulingPort {

    override suspend fun rescheduleFromNow() =
        IosArhamReminderDispatch.rescheduleFromNow(
            preferences = preferences,
            planner = planner,
            scheduler = scheduling,
        )
}
