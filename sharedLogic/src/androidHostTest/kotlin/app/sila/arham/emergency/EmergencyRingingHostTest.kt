package app.sila.arham.emergency

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import app.sila.feature.arham.domain.EmergencyRingingController
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات مضيف حلقة الطوارئ (T8-10 · AC-14 · §K.6 · Issue #29) فوق Robolectric:
 * بوابة الإذن `CALL_PHONE` فشلٌ هادئ (بلا منحٍ لا يُطلق FGS إطلاقاً — طلبُه مسؤولية
 * T8-11)، والبداية idempotent، وقناة الإشعار الجاري idempotent، وزر الإيقاف يصمد
 * بغياب Koin (بلا انهيار — §O.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyRingingHostTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun controller(): EmergencyRingingController = AndroidEmergencyRingingController(context = app)

    // ===== بوابة الإذن (فشل هادئ — المنفذ لا يطلق بلا CALL_PHONE) =====

    @Test
    fun `revoked CALL_PHONE refuses FGS launch without crashing`() {
        val ctl = controller()
        ctl.start(sessionId = 1L, contactId = 42L) // بلا منح — الشادو لا يمنح افتراضياً

        assertFalse(ctl.isRunning(sessionId = 1L), "مرفوض ⇒ لا جلسة تعمل")
        assertNull(shadowOf(app).nextStartedService, "بلا إذن ⇒ بلا FGS إطلاقاً")
    }

    @Test
    fun `granted CALL_PHONE launches FGS with ids-only extras`() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val ctl = controller()

        ctl.start(sessionId = 7L, contactId = 42L)

        assertTrue(ctl.isRunning(sessionId = 7L), "ممنوح ⇒ الجلسة تعمل")
        val launched = shadowOf(app).nextStartedService
        assertEquals(ArhamEmergencyRingingService::class.java.name, launched?.component?.className)
        assertEquals(
            7L,
            launched?.getLongExtra(ArhamEmergencyRingingService.EXTRA_SESSION_ID, -1L),
            "ids-only حصراً في الـ Intent (عقد T8-08)",
        )
        assertEquals(
            42L,
            launched?.getLongExtra(ArhamEmergencyRingingService.EXTRA_CONTACT_ID, -1L),
        )
    }

    @Test
    fun `duplicate start is idempotent - single FGS intent`() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val ctl = controller()

        ctl.start(sessionId = 7L, contactId = 42L)
        ctl.start(sessionId = 7L, contactId = 42L) // ثانية — بلا أثر (idempotent)

        assertEquals(
            ArhamEmergencyRingingService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )
        assertNull(
            shadowOf(app).nextStartedService,
            "البداية الثانية لا تطلق FGS ثانية",
        )
        assertTrue(ctl.isRunning(sessionId = 7L))
    }

    @Test
    fun `stop is idempotent and safe before start`() {
        val ctl = controller()
        ctl.stop() // قبل أي بداية — آمن (idempotent)
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        ctl.start(sessionId = 7L, contactId = 42L)
        ctl.stop()

        assertFalse(ctl.isRunning(sessionId = 7L), "بعد الإيقاف لا جلسة تعمل")
    }

    // ===== قناة الإشعار الجاري =====

    @Test
    fun `emergency channel ensure is idempotent`() {
        EmergencyNotificationChannel.ensure(app)
        EmergencyNotificationChannel.ensure(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(EmergencyNotificationChannel.CHANNEL_ID)
        assertEquals(EmergencyNotificationChannel.CHANNEL_ID, channel?.id, "ضمان القناة مرتين ⇒ واحدة")
    }

    // ===== زر الإيقاف — بلا Koin (عملية استقبال) ⇒ صمتٌ بلا انهيار (§O.5) =====

    @Test
    fun `stop broadcast without koin is a silent no-op`() {
        // بلا startKoin — `resolveArhamKoin` تعيد null والمستقبِل ينهي بهدوء (عملية استقبال).
        val receiver = EmergencyStopActionReceiver()
        receiver.onReceive(context = app, intent = Intent(app, EmergencyStopActionReceiver::class.java))
        // لا استثناء — goAsync/finish آمنان على Robolectric.
    }
}
