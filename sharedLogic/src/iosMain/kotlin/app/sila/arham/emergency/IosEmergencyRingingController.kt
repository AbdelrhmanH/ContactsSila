package app.sila.arham.emergency

import app.sila.arham.notification.ArhamNotificationCapability
import app.sila.arham.notification.IosNotificationCapabilityProbe
import app.sila.arham.notification.iosNotificationCenterIfPresent
import app.sila.core.model.Contact
import app.sila.feature.arham.domain.EmergencyRingingController
import app.sila.feature.arham.domain.EmergencySessionRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * منفذ حلقة الطوارئ على iOS (T8-13 · §L.6/§N.3 · AC-14) — مرآة [app.sila.arham.emergency.AndroidEmergencyRingingController]
 * لكن **بلا حلقة ذاتية**: لا توجد خدمة أمامية في iOS، فالسلوك المعتمد هو `tel:` متكرر **بتأكيد يدوي لكل محاولة**
 * (PLATFORM_PERMISSIONS §7). العقد ids-only: يُعاد قراءة الرقم من Room لحظة كل اتصال، ولا يُحتفظ برقم أو اسم في الحالة.
 *
 * - **start** (بعد إدراج الجلسة عبر `StartEmergencyRingingUseCase`): يعلّم الحالة كجارية ثم يفتح الاتصال
 *   الأول ويَنشر إشعار الطوارئ (فرع D7: النسخة القياسية دائماً، ونسخة Critical فقط حين يثبت الفحص
 *   الدعمَ والتَفعيل معاً).
 * - **placeCall** ([IosEmergencyCallPlacer]): إعادة اتصال لتأكيد المستخدم اليدوي — قراءة Room من جديد في كل مرة.
 * - **stop** (idempotent): يصفّر الحالة ويُزيل إشعار الطوارئ المُسلَّم (لا يوجد زر إيقاف على الإشعار —
 *   الإيقاف داخل التطبيق حصراً، مرآة §N.2 حيث "إشعار الإيقاف" مُعرَّف كميكانيكية أندرويد).
 * - **لا كشف ردّ**: حالة ANSWERED يُبلِغ عنها المستخدم من الواجهة (T8-14) — لا استماع لـ CXCall هنا.
 *
 * الحالة [runningSessionId] محصورة بخيط الواجهة (Main) مثل نظيرها في أندرويد (`@Synchronized` هناك
 * بسبب خيوط البايندر؛ هنا كل الاستدعاءات من الواجهة، والكوروتينات لا تلمسها) — لا `synchronized` في K/N.
 * لا `runBlocking` إطلاقاً: الأعمال تُطلق في نطاقٍ داخلي قابل للحقن (مرآة `IosArhamNotificationScheduler`).
 */
class IosEmergencyRingingController(
    private val repository: EmergencySessionRepository,
    private val strings: IosArhamEmergencyStrings,
    private val capabilityProbe: IosNotificationCapabilityProbe = IosNotificationCapabilityProbe(),
    private val actionContext: CoroutineContext = Dispatchers.Default,
    /**
     * مُزوِّد خيط الواجهة الرئيسية لفتح الرابط (كاشف مقابل مُقيَّم لحظة الطلب فقط — لا يُلمس عند
     * إنشاء الكائن، فتبقى اختبارات الـ iosTest بلا حاجة لـ Dispatchers.Main الحقيقي).
     */
    private val mainDispatcherProvider: () -> CoroutineContext = { Dispatchers.Main },
    private val canOpenUrl: (NSURL) -> Boolean = { UIApplication.sharedApplication().canOpenURL(it) },
    private val openUrl: (NSURL) -> Unit = { url ->
        UIApplication.sharedApplication().openURL(
            url = url,
            options = emptyMap<Any?, Any?>(),
            completionHandler = null,
        )
    },
    private val centerProvider: () -> UNUserNotificationCenter? = {
        // عبر نقطة الوعي بالغياب حصراً — اللمس الخام لـ currentNotificationCenter يُسقط
        // مضيف اختبار K/N (تنفيذية عارية بلا حزمة — سجل CI 42180300317).
        iosNotificationCenterIfPresent()
    },
) : EmergencyRingingController, IosEmergencyCallPlacer {

    private val scope = CoroutineScope(SupervisorJob() + actionContext)

    /** محصورة بخيط الواجهة — المرآة الدقيقة لـ `AndroidEmergencyRingingController.runningSessionId`. */
    private var runningSessionId: Long? = null

    override fun start(sessionId: Long, contactId: Long) {
        if (runningSessionId != null) {
            logTypeOnly("Emergency ringing already running; duplicate start ignored (idempotent)")
            return
        }
        runningSessionId = sessionId
        scope.launch {
            runCatching {
                // قراءة Room لحظة الاتصال (ids-only · §N.3) — لا يُحمل الرقم بين الطبقات.
                val contact = repository.getContactById(contactId)
                if (contact == null) {
                    logTypeOnly("Emergency start skipped; contact deleted")
                    return@launch
                }
                val phone = pickPrimaryPhone(contact)
                if (phone.isNullOrEmpty()) {
                    logTypeOnly("Emergency start skipped; contact has no phone number")
                    return@launch
                }
                if (!placeTelCall(phoneE164 = phone)) return@launch
                postActiveNotification(displayName = displayNameOf(contact))
            }.onFailure { logTypeOnly("Emergency start failed", it) }
        }
    }

    override fun stop() {
        if (runningSessionId == null) return // لا حلقة أصلاً — idempotent (§N.8)
        runningSessionId = null
        val center = centerProvider() ?: return // لا مركز إشعارات في الاختبارات — تخطٍّ هادئ (مرآة T8-12)
        // K/N: القوائم تُستورد List<*> — لا مُعالِج إكمال لهذا المُنتقي (تم التحقق عبر klib).
        center.removeDeliveredNotificationsWithIdentifiers(
            identifiers = listOf(EMERGENCY_NOTIFICATION_IDENTIFIER),
        )
    }

    override fun isRunning(sessionId: Long): Boolean = runningSessionId == sessionId

    /** إعادة اتصال يدوية (تأكيد المستخدم لكل محاولة — §N.3): قراءة Room من جديد ثم `tel:`. */
    override fun placeCall(contactId: Long) {
        scope.launch {
            runCatching {
                val contact = repository.getContactById(contactId)
                if (contact == null) {
                    logTypeOnly("Emergency re-dial skipped; contact deleted")
                    return@launch
                }
                val phone = pickPrimaryPhone(contact)
                if (phone.isNullOrEmpty()) {
                    logTypeOnly("Emergency re-dial skipped; contact has no phone number")
                    return@launch
                }
                placeTelCall(phoneE164 = phone)
            }.onFailure { logTypeOnly("Emergency re-dial failed", it) }
        }
    }

    /** يفتح `tel:` بعد فحص التوفر (iPad/بلا شريحة ← تخطٍّ هادئ؛ الواجهة تخفي الأزرار أصلاً — §L.4). */
    private suspend fun placeTelCall(phoneE164: String): Boolean {
        val url = NSURL.URLWithString(URLString = "$TEL_SCHEME$phoneE164") ?: return false
        if (!canOpenUrl(url)) {
            logTypeOnly("tel: scheme unavailable (no SIM / iPad); dial skipped")
            return false
        }
        withContext(mainDispatcherProvider()) { openUrl(url) }
        return true
    }

    /**
     * إشعار "حلقة الطوارئ نشطة" (فرع D7 · §L.6): يُنشر بعد نجاح الاتصال وحين يوجد مركز إشعارات.
     * النسخة القياسية دائماً (fallback-first)؛ نسخة Critical فقط إذا كان الدعم مُفعلاً — والإذن
     * **لا يُطلب ولا يُنتظر** (D7: صلاحية Apple الخاصة بالتنبيهات الحرجة لا تُضمن أبداً ولا تحجب البديل).
     *
     * استدعاءات `UNUserNotificationCenter` هنا على خيط النطاق (لا قفزة Main) — آمن وموثَّق:
     * المركز thread-safe حسب Apple، بخلاف `openURL` الذي يُلزم خيط الواجهة الرئيسية حصراً.
     */
    private suspend fun postActiveNotification(displayName: String) {
        val center = centerProvider() ?: return
        val capability = runCatching { capabilityProbe.currentCapability(center) }.getOrNull() ?: return
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = EMERGENCY_NOTIFICATION_IDENTIFIER,
            content = buildActiveContent(
                displayName = displayName,
                critical = capability.isCriticalEligible(),
            ),
            // لا مشغِّل = نشر فوري (المرآة الدقيقة لإشعار FGS الجاري في أندرويد).
            trigger = null,
        )
        center.addNotificationRequest(request = request) { error ->
            if (error != null) logNSError("Emergency notification rejected", error)
        }
    }

    /**
     * بناء محتوى الإشعار — داخلي/نقي ليُختبر بلا مركز إشعارات حقيقي.
     * Critical: صوت حرج + مستوى مقاطعة حرج (يتطلب صلاحية Apple — يُستخدم فقط بعد فحص [isCriticalEligible]).
     */
    internal fun buildActiveContent(displayName: String, critical: Boolean): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            // K/N: خصائص ObjC تُستورد val + مُعيّنات setX(...) (تحقق klib 2.4 — مرآة T8-12).
            setTitle(strings.activeTitle())
            setBody(strings.activeBody(displayName))
            setSound(
                if (critical) {
                    UNNotificationSound.defaultCriticalSoundWithAudioVolume(volume = CRITICAL_ALERT_VOLUME)
                } else {
                    UNNotificationSound.defaultSound
                },
            )
            if (critical) setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelCritical)
        }

    internal companion object {

        /** مُعرِّف ثابت لضمان الاستبدال لا التراكم عند كل محاولة (مرآة `REMINDER_IDENTIFIER`). */
        const val EMERGENCY_NOTIFICATION_IDENTIFIER = "app.sila.arham.emergency.active"

        const val TEL_SCHEME = "tel:"

        /** مستوى الصوت الحرج الموصى به من Apple (0..1). */
        const val CRITICAL_ALERT_VOLUME = 1.0f

        /** اختيار الرقم: الأساسي ثم الأول — المرآة الحرفية لحلقة أندرويد. */
        internal fun pickPrimaryPhone(contact: Contact): String? =
            contact.phones.firstOrNull { it.isPrimary }?.e164
                ?: contact.phones.firstOrNull()?.e164

        internal fun displayNameOf(contact: Contact): String =
            contact.name.displayName.takeIf { it.isNotBlank() } ?: EMPTY_DISPLAY_NAME_FALLBACK

        private const val EMPTY_DISPLAY_NAME_FALLBACK = " "

        fun logTypeOnly(message: String, error: Throwable? = null) {
            // بلا بيانات شخصية في السجلات إطلاقاً (§O.5) — الأنواع فقط.
            if (error == null) println(message) else println("$message: ${error::class.simpleName}")
        }

        fun logNSError(message: String, error: platform.Foundation.NSError) {
            println("$message: ${error.domain}/${error.code}")
        }
    }
}

/**
 * فرع D7 النقي (قابل للاختبار بلا UserNotifications): النسخة الحرجة لا تُستخدم إلا إذا
 * أعلن الفحص أن الجهاز يدعم التنبيهات الحرجة **و** أن الميزة مُفعلة للنظام.
 */
internal fun ArhamNotificationCapability.isCriticalEligible(): Boolean =
    criticalAlertSupported && criticalAlertEnabled
