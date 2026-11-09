package app.sila.arham.notification

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import app.sila.feature.settings.domain.ArhamReminderSchedulingPort

/**
 * غلاف أندرويد لمنفذ إعادة الجدولة الفورية من الإعدادات (S2-13 · تذكرة #71 · ADR-0016):
 * تفويض حرف إلى [ArhamReminderDispatch.rescheduleFromNow] — القرار المُجرَّب نفسه
 * (تعطيل ⇒ لا جدولة؛ تفعيل ⇒ خطة من `now` عبر `PlanArhamDailyNotificationUseCase`
 * وإسنادها للعقد المشترك [ArhamAlarmScheduler]) بلا نسخٍ ثاني قابل للانحراف.
 */
class AndroidArhamReminderSchedulingPort(
    private val preferences: UserPreferencesDataSource,
    private val planner: PlanArhamDailyNotificationUseCase,
    private val scheduler: ArhamAlarmScheduler,
) : ArhamReminderSchedulingPort {

    override suspend fun rescheduleFromNow() =
        ArhamReminderDispatch.rescheduleFromNow(
            preferences = preferences,
            planner = planner,
            scheduler = scheduler,
        )
}
