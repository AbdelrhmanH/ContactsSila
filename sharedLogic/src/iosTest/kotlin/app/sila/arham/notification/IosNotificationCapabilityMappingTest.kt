package app.sila.arham.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSettingDisabled
import platform.UserNotifications.UNNotificationSettingEnabled
import platform.UserNotifications.UNNotificationSettingNotSupported

/**
 * اختبارات خرائط قدرة الإشعارات من أنواع cinterop الفعلية إلى النموذج المشترك
 * (T8-12 · بوابة D7) — كل حالة ثابتٍ منصّيٍّ حقيقي تُحوَّل بأمانة، والتنبيهات الحرجة
 * ثنائيةُ (سند/تمكين) لا تُفترَض منحَ استحقاق قط (W-2: القرار خارج نطاق الصِلَة).
 */
class IosNotificationCapabilityMappingTest {

    // ===== حالة إذن الإشعارات =====

    @Test
    fun authorizationStatus_allPlatformValues_mirrorFaithfully() {
        assertEquals(
            ArhamAuthorizationStatus.NOT_DETERMINED,
            mapAuthorizationStatus(UNAuthorizationStatusNotDetermined),
        )
        assertEquals(
            ArhamAuthorizationStatus.DENIED,
            mapAuthorizationStatus(UNAuthorizationStatusDenied),
        )
        assertEquals(
            ArhamAuthorizationStatus.AUTHORIZED,
            mapAuthorizationStatus(UNAuthorizationStatusAuthorized),
        )
        assertEquals(
            ArhamAuthorizationStatus.PROVISIONAL,
            mapAuthorizationStatus(UNAuthorizationStatusProvisional),
        )
        assertEquals(
            ArhamAuthorizationStatus.EPHEMERAL,
            mapAuthorizationStatus(UNAuthorizationStatusEphemeral),
        )
    }

    // ===== إعداد التنبيهات الحرجة: ثنائية (سند، تمكين) =====

    @Test
    fun criticalAlertSetting_enabled_supportedAndEnabled() {
        val (supported, enabled) = mapCriticalAlertSetting(UNNotificationSettingEnabled)
        assertTrue(supported, "الإعداد مُبلغ ممكّن ⇒ المنصة تسند التنبيهات الحرجة أصلاً")
        assertTrue(enabled, "والتنبيه الحرج ممكّن فعلاً")
    }

    @Test
    fun criticalAlertSetting_disabled_supportedButDisabled() {
        val (supported, enabled) = mapCriticalAlertSetting(UNNotificationSettingDisabled)
        assertTrue(supported, "الإعداد مُبلغ معطّل ⇒ السند موجود (الإعداد معرَّف للمنصة)")
        assertFalse(enabled, "لكن التمكين الحالي معطّل — المسار القياسي يحكم (fallback-first)")
    }

    @Test
    fun criticalAlertSetting_notSupported_neitherSupportedNorEnabled() {
        val (supported, enabled) = mapCriticalAlertSetting(UNNotificationSettingNotSupported)
        assertFalse(supported, "بلا سند (المحاكي/الجهاز بلا استحقاق) — لا ادعاء قدرة قط")
        assertFalse(enabled)
    }
}
