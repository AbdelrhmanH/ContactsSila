package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamStatus
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * حالة استخدام: تخطيط إشعار أرحام اليومي (§M + D2 المصادَق — T8-08، Issue #29).
 *
 * قرارٌ حتّي مشترك (Android/iOS) بنقطتا استهلاك في المستقبِل (المستقبِل يعيد حسابه
 * عند كل إطلاق — D2 §8 «الجدولة مشتقة من Room والتفضيلات»):
 * - **موعد الإطلاق:** الوقت المفضّل محلياً اليوم إن كان مستقبلاً وإلا غداً، ثم إزاحة
 *   ساعات الهدوء (`QuietHours`) — قد تدفع عبر منتصف الليل (23→8 الافتراضي).
 * - **المحتوى:** تنبيه مناسبة اليوم (والغد إذا مُكِّن «اليوم السابق» — ComputeOccasionAlertsUseCase
 *   أصل القاعدة) أولويةً لأنها حساسة للوقت، وإلا الرسالة المجمعة من قائمة الأولويات
 *   (المتأخرون حصراً — المرتبة 01 عنواناً و«+N» للبقية)؛ ولا إشعار أصلاً بلا متأخر
 *   ولا مناسبة — لا تُختلق حالة إلحاح.
 *
 * **الميزانية ≤ 1/يوم (§M):** يفرضها البناء — مستقبِل واحد ثابت الـ PendingIntent
 * يُستبدل ولا يتراكم، وإطلاق واحد يومياً؛ إعادة الجدولة (تشغيل/إقلاع/فعل) تحسب الموعد
 * نفسه حتماً من `now` نفسها. تعطيل الميزة يمنع الجدولة كلياً (لا موعد بلا محتوى مسموح).
 *
 * المنطقة الزمنية محقونة بلا ساعة (نمط `ObserveUpcomingOccasionsUseCase` — انظر O-4:
 * الافتراضي للنظام عند البناء عبر Koin حصراً).
 */
class PlanArhamDailyNotificationUseCase(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val observeUpcomingOccasions: ObserveUpcomingOccasionsUseCase,
    private val clock: AppClock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {

    /**
     * يبني خطة الإشعار عند اللحظة الحالية: الموعد القادم + محتواه المرشح — المدخل
     * المعياري لكل UseCase في الميثاق (نمط operator invoke الموحَّد).
     *
     * @param preferences تفضيلات المستخدم (الميزة، الوقت المفضل، ساعات الهدوء، اليوم السابق).
     */
    suspend operator fun invoke(preferences: UserPreferences): ArhamNotificationPlan = plan(preferences)

    /**
     * يبني خطة الإشعار عند اللحظة الحالية: الموعد القادم + محتواه المرشح.
     *
     * @param preferences تفضيلات المستخدم (الميزة، الوقت المفضل، ساعات الهدوء، اليوم السابق).
     */
    suspend fun plan(preferences: UserPreferences): ArhamNotificationPlan {
        val now = clock.now()
        val fireAt = nextFireTime(now = now, preferences = preferences)
        val content = computeContent(preferences = preferences)
        return ArhamNotificationPlan(fireAtMillis = fireAt, content = content)
    }

    /**
     * أقرب موعد إطلاق: الوقت المفضّل القادم (اليوم إن كان مستقبلاً وإلا غداً)
     * بعد إزاحة ساعات الهدوء. المقارنة بميلي ثانية حصراً (توافق kotlinx/kotlin.time
     * للـ Instant في هذا الإصدار — درس الترجمة الأول).
     */
    private fun nextFireTime(now: Instant, preferences: UserPreferences): Long {
        val nowMillis = now.toEpochMilliseconds()
        val local = now.toLocalDateTime(timeZone)
        val preferredToday = local.date.atTime(preferences.arhamNotificationHour, preferences.arhamNotificationMinute)
        val preferredTodayMillis = preferredToday.toInstant(timeZone).toEpochMilliseconds()
        val slotMillis = if (preferredTodayMillis > nowMillis) {
            preferredTodayMillis
        } else {
            local.date.plus(1, DateTimeUnit.DAY)
                .atTime(preferences.arhamNotificationHour, preferences.arhamNotificationMinute)
                .toInstant(timeZone)
                .toEpochMilliseconds()
        }
        return QuietHours.deferIfQuiet(
            fireAtMillis = slotMillis,
            timeZone = timeZone,
            startHour = preferences.quietHoursStartHour,
            endHour = preferences.quietHoursEndHour,
        )
    }

    /** محتوى الإشعار عند اللحظة الحالية: مناسبة أولويةً، وإلا الرسالة المجمعة، وإلا لا شيء. */
    private suspend fun computeContent(preferences: UserPreferences): ArhamNotificationContent? {
        val occasions = observeUpcomingOccasions().first()
        val alerts = ComputeOccasionAlertsUseCase()(
            occasions = occasions,
            includeDayBefore = preferences.arhamOccasionDayBeforeEnabled,
        )
        val occasionAlert = alerts
            .sortedWith(
                compareBy({ it.kind.ordinal }, { it.occasion.displayName }, { it.occasion.contactId }),
            )
            .firstOrNull()
        if (occasionAlert != null) {
            return ArhamNotificationContent.Occasion(
                kind = occasionAlert.kind,
                type = occasionAlert.occasion.type,
                displayName = occasionAlert.occasion.displayName,
            )
        }

        // قائمة الأولويات هي المصدر الوحيد لـ«المتأخرين» — لا إعادة حساب إلحاح هنا.
        val overdue = getPriorityList().first().filter { it.status == ArhamStatus.OVERDUE }
        val headline = overdue.firstOrNull() ?: return null
        return ArhamNotificationContent.Digest(
            headlineDisplayName = headline.displayName,
            additionalOverdueCount = overdue.size - 1,
        )
    }
}
