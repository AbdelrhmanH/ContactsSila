package app.sila.arham.notification

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSetting
import platform.UserNotifications.UNNotificationSettingDisabled
import platform.UserNotifications.UNNotificationSettingEnabled
import platform.UserNotifications.UNNotificationSettingNotSupported
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * قدرة إشعارات المنصة كما أبلغتها iOS (T8-12 · بوابة D7) — نموذج قراراتٍ محتٍّ مشترك:
 * - [authorizationStatus] حالة إذن الإشعارات الفعلية (غير مفترَضة — لا يُفترض منحٌ قط).
 * - [criticalAlertSupported] هل تدعم المنصة التنبيهات الحرجة أصلاً (المحاكي/الجهاز بلا
 *   استحقاق يُبلِغ `notSupported`) — **لا يعني منحَ استحقاق** أبداً (W-2: القرار خارج
 *   نطاق الصِلَة؛ مسار الطوارئ fallback-first لا يُحجَّم عليه — D7).
 */
data class ArhamNotificationCapability(
    val authorizationStatus: ArhamAuthorizationStatus,
    val criticalAlertSupported: Boolean,
    val criticalAlertEnabled: Boolean,
)

/** حالة إذن الإشعارات — مرآة `UNAuthorizationStatus` بلا أنواع منصة. */
enum class ArhamAuthorizationStatus {
    NOT_DETERMINED,
    DENIED,
    AUTHORIZED,
    PROVISIONAL,
    EPHEMERAL,
}

/** خريطة حالة الإذن من نوع المنصة إلى النموذج المشترك — نقي ومُختبر. */
internal fun mapAuthorizationStatus(status: UNAuthorizationStatus): ArhamAuthorizationStatus =
    when (status) {
        UNAuthorizationStatusNotDetermined -> ArhamAuthorizationStatus.NOT_DETERMINED
        UNAuthorizationStatusDenied -> ArhamAuthorizationStatus.DENIED
        UNAuthorizationStatusAuthorized -> ArhamAuthorizationStatus.AUTHORIZED
        UNAuthorizationStatusProvisional -> ArhamAuthorizationStatus.PROVISIONAL
        UNAuthorizationStatusEphemeral -> ArhamAuthorizationStatus.EPHEMERAL
        else -> ArhamAuthorizationStatus.NOT_DETERMINED
    }

/** خريطة حالة الإعداد (`enabled/disabled/notSupported`) إلى الثنائية (السند، التمكين). */
internal fun mapCriticalAlertSetting(setting: UNNotificationSetting): Pair<Boolean, Boolean> =
    when (setting) {
        UNNotificationSettingEnabled -> true to true
        // المنصة تُبلغ عن الإعداد أصلاً ⇒ السند موجود، لكنه معطّل — المسار القياسي يحكم
        // (fallback-first، D7): الاختبار `criticalAlertSetting_disabled_supportedButDisabled`.
        UNNotificationSettingDisabled -> true to false
        UNNotificationSettingNotSupported -> false to false
        else -> false to false
    }

/**
 * بوابة استقصاء القدرة (T8-12 · D7): يقرأ إعدادات مركز الإشعارات الفعلية — يُستهلك من
 * مسار الطوارئ (T8-13) ليقرر المسار القياسي دون ادعاء تنبيهٍ حرج مُسلَّم قط.
 */
class IosNotificationCapabilityProbe {

    suspend fun currentCapability(center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter()): ArhamNotificationCapability =
        suspendCancellableCoroutine { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings: UNNotificationSettings? ->
                if (settings == null) {
                    // غياب الإعدادات = قدرة غير معلومة — أقل الحالات صلاحيةً (fail-safe).
                    continuation.resume(
                        ArhamNotificationCapability(
                            authorizationStatus = ArhamAuthorizationStatus.NOT_DETERMINED,
                            criticalAlertSupported = false,
                            criticalAlertEnabled = false,
                        ),
                    )
                    return@getNotificationSettingsWithCompletionHandler
                }
                val (supported, enabled) = mapCriticalAlertSetting(settings.criticalAlertSetting)
                continuation.resume(
                    ArhamNotificationCapability(
                        authorizationStatus = mapAuthorizationStatus(settings.authorizationStatus),
                        criticalAlertSupported = supported,
                        criticalAlertEnabled = enabled,
                    ),
                )
            }
        }
}
