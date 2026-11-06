package app.sila.arham.notification

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.ArhamNotificationContent
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSError
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * جدولة تذكير أرحام اليومي على iOS (T8-12 · §M · D2 المصادَق · خطة سطر 552) — تنفيذ
 * [ArhamAlarmScheduler] المشترك فوق `UNUserNotificationCenter` بـ
 * `UNCalendarNotificationTrigger` يومي متكرر على الوقت الفعّال (بعد إزاحة ساعات الهدوء
 * بواسطة الدالة المشتركة — القاعدة في النطاق المشترك حصراً).
 *
 * **دلالة الاستبدال (ميزانية ≤ 1 ظاهر/يوم):** مُعرّف إشعار ثابت — `add` بنفس المعرّف
 * يستبدل الطلب المعلّق داخل النظام (مرآة `FLAG_UPDATE_CURRENT` أندرويد).
 *
 * **حدود المنصة المعلنة بصدق (KDoc لا يخفيها):** محتوى الإشعار المحلي يُحسب وقتَ الجدولة
 * من المخطط المشترك (مرآة قرار `ArhamNotificationPlan`) ويُعاد اشتقاقه عند كل إطلاق
 * عملية/تغيير تفضيل/«ذكّرني غداً» (D2 §8 نفسها) — لا توجد نقطة إعادة حساب عند لحظة
 * الإطلاق للإشعارات المحلية على iOS فالمحتوى قد يتصاعم إن تغيّرت البيانات بعد آخر
 * اشتقاق؛ هذا فارقٌ معلوم عن مسار أندرويد (المستقبِل يعيد الحساب وقت الإطلاق).
 *
 * **الإذن لا يُطلب من هنا** (توقيت الطلب قرار واجهة T8-14): المرفوض/غير المحدد يجعل
 * التسليم صامتاً لكن الجدولة تبقى بلا كلفة (مرآة أندرويد: التفضيل يحكم الجدولة والمنصة
 * تحكم الظهور). الإخفاقات تُسجَّل بنوعٍ حصراً — بلا PII ولا انهيار (§O.5).
 */
class IosArhamNotificationScheduler(
    private val planner: PlanArhamDailyNotificationUseCase,
    private val preferences: UserPreferencesDataSource,
    private val strings: IosArhamNotificationStrings,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    /** نطاق الجدولة غير المتزامنة — قابل للحقن للاختبار (مرآة نطاق المستقبِل أندرويد). */
    private val schedulingContext: CoroutineContext = Dispatchers.Default,
    /**
     * نقطةُ خياطة مركز الإشعارات — `null` يعني «المركز غير متاح» فتُهمل العملية
     * بسجلٍّ بنوعٍ حصراً (بلا انهيار — §O.5)؛ الحقن يسمح باختبار مسار غياب المركز.
     */
    private val centerProvider: () -> UNUserNotificationCenter? = {
        UNUserNotificationCenter.currentNotificationCenter()
    },
) : ArhamAlarmScheduler, IosArhamReminderScheduling {

    override fun scheduleAt(fireAtMillis: Long) {
        // غير متزامن عمداً (نمط `ArhamNotificationActionReceiver` أندرويد): تخطيط المحتوى
        // يقرأ DataStore/Room — لا `runBlocking` على خيط المستدعي إطلاقاً؛ الاستبدال
        // بالمُعرّف الثابت يجعل التداخل بين جدولتين متتاليتين بلا أثر (الأخير يفوز).
        CoroutineScope(SupervisorJob() + schedulingContext).launch {
            runCatching { scheduleSuspended(fireAtMillis) }
                .onFailure { logTypeOnly("Arham reminder scheduling failed", it) }
        }
    }

    /** يلغي الطلب المعلّق — مسار تعطيل الميزة/غياب المحتوى (الزنّاد المتكرر لا يُترك يتائح). */
    override fun cancelPending() {
        val center = centerProvider() ?: return // بلا مركز لا معلّق أصلاً — صمتٌ بلا انهيار
        // K/N: الدفعات تُمسح بقائمة معرّفات — لا نسخةَ مفردة ولا معالجَ إتمام في الجسر.
        center.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf(REMINDER_IDENTIFIER),
        )
    }

    /** جسد الجدولة معلّقاً: خطة المشترك → محتوى الحزمة → زنّاد يومي على الوقت الفعّال. */
    private suspend fun scheduleSuspended(fireAtMillis: Long) {
        val prefs = preferences.preferences.first()
        val planContent = planner.plan(prefs).content
        if (planContent == null) {
            // لا متأخر ولا مناسبة عند آخر اشتقاق ⇒ لا إشعار (لا تُختلق حالة إلحاح — درس D.3).
            cancelPending()
            return
        }
        val center = centerProvider() ?: run {
            logTypeOnly("Arham reminder center unavailable", IllegalStateException("null center"))
            return
        }
        center.addNotificationRequest(
            request = reminderRequest(content = planContent, fireAtMillis = fireAtMillis),
        ) { error ->
            if (error != null) logTypeOnly("Arham reminder scheduling rejected", error)
        }
    }

    /**
     * بناء طلب الإشعار من محتوى الخطة — نقيٌّ من المنصّة الجانبية (لا مركز ولا تفضيلات:
     * الكائنات تُبنى بلا تسجيل) — `identifier` الثابت هو عقد الاستبدال (ميزانية ≤ 1/يوم)
     * والزنّاد يومي متكرر (خطة سطر 552). مُختبر على المحاكي بلا مركز إشعارات.
     */
    internal fun reminderRequest(content: ArhamNotificationContent, fireAtMillis: Long): UNNotificationRequest =
        UNNotificationRequest.requestWithIdentifier(
            identifier = REMINDER_IDENTIFIER,
            content = content.toPlatformContent(),
            trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = fireComponents(fireAtMillis),
                repeats = true,
            ),
        )

    /** محتوى دلالي مشترك ← محتوى إشعار iOS بنصوص الحزمة حصراً (لا PII خارج النص المعروض). */
    internal fun ArhamNotificationContent.toPlatformContent(): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            // K/N: خصائص المحتوى الكائنية val + مُتثبتات `setX(...)` (نمط klib 2.4).
            setTitle(strings.digestTitle())
            setBody(
                when (this@toPlatformContent) {
                    is ArhamNotificationContent.Digest -> strings.digestBody(
                        headlineDisplayName = headlineDisplayName,
                        additionalOverdueCount = additionalOverdueCount,
                    )
                    is ArhamNotificationContent.Occasion -> strings.occasionBody(
                        kind = kind,
                        type = type,
                        displayName = displayName,
                    )
                },
            )
            setSound(UNNotificationSound.defaultSound)
        }

    /**
     * مركّبات الإطلاق من إيبوك ميلي — تفويضٌ للدالة النقية [fireComponentsOf].
     */
    internal fun fireComponents(fireAtMillis: Long): NSDateComponents =
        fireComponentsOf(fireAtMillis = fireAtMillis, timeZone = timeZone)

    internal companion object {
        /** مُعرّف إشعار التذكير الثابت — مفتاح الاستبدال حصراً (لا معنى عشوائياً). */
        const val REMINDER_IDENTIFIER = "app.sila.arham.reminder.daily"

        /** سجلّ بنوعٍ حصراً — بلا PII (§O.5)؛ وصف الخطأ من نوعه/رمزه فقط. */
        fun logTypeOnly(message: String, error: Throwable) {
            println("$message: ${error::class.simpleName}")
        }

        fun logTypeOnly(message: String, error: NSError) {
            println("$message: ${error.domain}/${error.code}")
        }
    }
}

/**
 * مركّبات الإطلاق من إيبوك ميلي — دالة نقية (مُختبرة على المحاكي): السنة/الشهر/اليوم/
 * الساعة/الدقيقة في المنطقة المحقونة — التاريخ الكامل يضمن أول إطلاق صحيح بعد الإزاحة
 * عبر منتصف الليل، والزنّاد المتكرر يعتمد الساعة/الدقيقة دورياً (خطة سطر 552:
 * `UNCalendarNotificationTrigger` يومي).
 */
internal fun fireComponentsOf(fireAtMillis: Long, timeZone: TimeZone): NSDateComponents {
    val local: LocalDateTime =
        Instant.fromEpochMilliseconds(fireAtMillis).toLocalDateTime(timeZone)
    return NSDateComponents().apply {
        year = local.year.toLong()
        month = local.monthNumber.toLong()
        day = local.dayOfMonth.toLong()
        hour = local.hour.toLong()
        minute = local.minute.toLong()
    }
}
