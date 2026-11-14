package app.sila.arham.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات انحدار A-N08 لخط إشعارات أرحام فوق Robolectric وKoin الحقيقي:
 * نوع الـ PendingIntent وهويته (`2002`/`2003`/`2004`)، تجاوز `MATCH_DEFAULT_ONLY` بمكوّن صريح،
 * مسح إشعار `2001` عند معالجة `ACTION_CALL_NOW` في دورة حياة `MainActivity`، عدم التداخل مع
 * `A-N07` و`A-N09`، وبلا PII في أي Intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArhamNotificationActionHostTest {

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val koin get() = KoinPlatformTools.defaultContext().get()

    @Before
    fun setUp() {
        startKoin {
            androidContext(app)
            modules(createSilaSharedModule())
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun callNowActionIsADirectImmutableActivityPendingIntentToTheLauncher() = runBlocking {
        postDigestNotification(app, koin)
        val notification = app.testNotificationManager().activeNotifications.single().notification
        val callNow = shadowOf(notification.actions[0].actionIntent)

        assertTrue(callNow.isActivity(), "«اتصل الآن» يطلق النشاط مباشرةً (PendingIntent.getActivity)")
        assertEquals(ArhamNotificationPoster.CALL_NOW_REQUEST_CODE, callNow.requestCode)
        assertTrue(callNow.isImmutable(), "توافق Android 12+: FLAG_IMMUTABLE")

        val expectedComponent = ComponentName(app.packageName, ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME)
        val intent = callNow.getSavedIntent()
        assertEquals(ArhamNotificationPoster.ACTION_CALL_NOW, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, intent.`package`)
        assertEquals(expectedComponent, intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertNull(intent.data, "بلا وجهة اتصال — فتح التطبيق حصراً (لا مكالمة)")
        assertEquals(0, intent.extras?.size() ?: 0, "بلا PII في أي Intent")

        val legacyBroadcast = PendingIntent.getBroadcast(
            app,
            ArhamNotificationPoster.CALL_NOW_REQUEST_CODE,
            Intent(app, ArhamNotificationActionReceiver::class.java)
                .setAction(ArhamNotificationPoster.ACTION_CALL_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_NO_CREATE,
        )
        assertNull(legacyBroadcast, "لا بثّ بحساب 2002 — الهوية انتقلت للنشاط")
    }

    @Test
    fun explicitLauncherComponentResolvesUnderMatchDefaultOnlyWhileImplicitFails() = runBlocking {
        val mainComponent = registerRealManifestMainActivity(app)

        val legacyImplicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNull(
            app.packageManager.resolveActivity(legacyImplicit, PackageManager.MATCH_DEFAULT_ONLY),
            "النية الضمنية بلا CATEGORY_DEFAULT تعطي null (سبب aInfo is null و -91)",
        )

        postDigestNotification(app, koin)
        val notification = app.testNotificationManager().activeNotifications.single().notification
        val callNowIntent = shadowOf(notification.actions[0].actionIntent).getSavedIntent()
        val contentIntent = shadowOf(notification.contentIntent).getSavedIntent()

        val callResolved = app.packageManager.resolveActivity(callNowIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val contentResolved = app.packageManager.resolveActivity(contentIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(mainComponent.className, callResolved?.activityInfo?.name)
        assertEquals(mainComponent.className, contentResolved?.activityInfo?.name)
    }

    @Test
    fun callNowActionResolvesUnderMatchDefaultOnlyAndClearsNotificationWhenHandledByActivity() = runBlocking {
        val mainComponent = registerRealManifestMainActivity(app)
        postDigestNotification(app, koin)
        val notification = app.testNotificationManager().activeNotifications.single().notification
        val callNow = notification.actions[0].actionIntent
        val contentIntent = shadowOf(notification.contentIntent).getSavedIntent()
        assertTrue(shadowOf(callNow).isActivity(), "المقدمة: النية نشاط إطلاقاً (A-N08)")

        // ShadowPendingIntent.send() يلتف على PackageManager، لذا نتحقق صراحةً من
        // حلّ النية الصادرة تحت MATCH_DEFAULT_ONLY مقابل <intent-filter> المانيفست الفعلي.
        callNow.send()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started, "الإطلاق الفعلي: نشاط بدأ فعلاً")
        val resolved = app.packageManager.resolveActivity(started, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(mainComponent.className, resolved?.activityInfo?.name)
        assertEquals(ArhamNotificationPoster.ACTION_CALL_NOW, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, started.`package`)
        assertEquals(mainComponent, started.component)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertNull(started.data)
        assertEquals(0, started.extras?.size() ?: 0)

        // لا يُمسح الإشعار استباقياً قبل استقبال النشاط؛ وعند الاستقبال في onCreate/onNewIntent يُمسح 2001.
        assertEquals(1, app.testNotificationManager().activeNotifications.size)
        assertTrue(ArhamReminderDispatch.onLaunchIntentHandled(app, started))
        assertEquals(0, app.testNotificationManager().activeNotifications.size, "إشعار 2001 يُمسح عند فتح MainActivity")
        assertEquals(Intent.ACTION_MAIN, started.action, "استهلاك الوسم يمنع تكرار المسح عند التدوير")
        assertFalse(ArhamReminderDispatch.onLaunchIntentHandled(app, started))
        assertFalse(ArhamReminderDispatch.onLaunchIntentHandled(app, contentIntent))
        assertFalse(ArhamReminderDispatch.onLaunchIntentHandled(app, null))

        // محاكاة جولة ثانية (استلام عبر onNewIntent دون إعادة إنشاء النشاط).
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)
        val warmAction = app.testNotificationManager().activeNotifications.single().notification.actions[0].actionIntent
        warmAction.send()
        val warmStarted = shadowOf(app).nextStartedActivity
        assertTrue(ArhamReminderDispatch.onLaunchIntentHandled(app, warmStarted))
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }

    @Test
    fun remindTomorrowStaysABroadcastToTheActionReceiver() = runBlocking {
        postDigestNotification(app, koin)
        val notification = app.testNotificationManager().activeNotifications.single().notification
        val remind = shadowOf(notification.actions[1].actionIntent)

        assertTrue(remind.isBroadcast(), "«ذكّرني غداً» يبقى بثّاً — يعمل على المنصتين (A-N09)")
        assertEquals(ArhamNotificationPoster.REMIND_TOMORROW_REQUEST_CODE, remind.requestCode)
        assertTrue(remind.isImmutable(), "توافق Android 12+: FLAG_IMMUTABLE")

        val intent = remind.getSavedIntent()
        assertEquals(ArhamNotificationPoster.ACTION_REMIND_TOMORROW, intent.action)
        assertEquals(ArhamNotificationActionReceiver::class.java.name, intent.component?.className)
        assertEquals(0, intent.extras?.size() ?: 0, "بلا extras (لا PII)")
    }

    @Test
    fun contentTapStaysTheProvenLauncherActivityPendingIntent() = runBlocking {
        postDigestNotification(app, koin)
        val content = shadowOf(
            app.testNotificationManager().activeNotifications.single().notification.contentIntent,
        )

        assertTrue(content.isActivity(), "نقرة المحتوى كما هي — لا تداخل مع A-N07")
        assertEquals(ArhamNotificationPoster.CONTENT_TAP_REQUEST_CODE, content.requestCode)
        assertTrue(content.isImmutable(), "توافق Android 12+: FLAG_IMMUTABLE")

        val intent = content.getSavedIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, intent.`package`)
        assertEquals(
            ComponentName(app.packageName, ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME),
            intent.component,
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals(0, intent.extras?.size() ?: 0, "بلا extras (لا PII)")
    }
}
