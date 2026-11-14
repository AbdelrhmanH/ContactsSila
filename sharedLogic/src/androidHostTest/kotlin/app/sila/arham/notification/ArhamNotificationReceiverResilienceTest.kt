package app.sila.arham.notification

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * اختبارات صمود [ArhamNotificationActionReceiver]:
 * رفض الأفعال الأجنبية قبل `goAsync()`، إعادة رمي [CancellationException] دون ابتلاع،
 * التعامل الهادئ مع غياب Koin، ومسح إشعار 2001 في `finally` مع صمود فشل المسح نفسه.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArhamNotificationReceiverResilienceTest {

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
    fun notificationIsClearedEvenWhenTheActionHandlerFails() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        val failingStart = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("simulated background-start abort")
            }
        }
        ArhamNotificationActionReceiver().runAction(
            appContext = failingStart,
            action = ArhamNotificationPoster.ACTION_CALL_NOW,
        )

        assertEquals(
            0,
            app.testNotificationManager().activeNotifications.size,
            "التنظيف في finally: الإشعار يُخفى حتى عند الرمي",
        )
    }

    @Test
    fun legacyBroadcastCallNowFallbackStillOpensAppAndClearsNotification() = runBlocking {
        postDigestNotification(app, koin)
        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_CALL_NOW,
        )

        assertNotNull(shadowOf(app).nextStartedActivity, "التطبيق فُتح (المسار الاحتياطي يظل يعمل)")
        assertEquals(0, app.testNotificationManager().activeNotifications.size, "التنظيف بعد النجاح كذلك")
    }

    @Test
    fun cancellationDuringActionPropagatesCancellationExceptionAndStillClearsNotification() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        val cancellingStart = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw CancellationException("simulated coroutine cancellation")
            }
        }
        assertFailsWith<CancellationException> {
            ArhamNotificationActionReceiver().runAction(
                appContext = cancellingStart,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
            )
        }
        assertEquals(
            0,
            app.testNotificationManager().activeNotifications.size,
            "الإلغاء لا يُبتلع والتنظيف في finally يظل مضموناً",
        )
    }

    @Test
    fun foreignActionInOnReceiveIsIgnoredWithoutCancellingNotification() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        ArhamNotificationActionReceiver().onReceive(
            app,
            Intent("com.example.FOREIGN_ACTION"),
        )
        delay(200L)
        assertEquals(
            1,
            app.testNotificationManager().activeNotifications.size,
            "الفعل الغريب يُتجاهل فوراً قبل goAsync() ولا يلغي إشعار 2001",
        )
    }

    @Test
    fun remindTomorrowActionReschedulesOneFutureAlarmAndClearsTheNotification() = runBlocking {
        postDigestNotification(app, koin)
        val before = System.currentTimeMillis()
        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_REMIND_TOMORROW,
        )

        val alarms = shadowOf(app.testAlarmManager()).scheduledAlarms
        assertEquals(1, alarms.size, "لا تذكيرات مكررة — إعادة الجدولة تستبدل (A-N10)")
        assertTrue(alarms.single().getTriggerAtMs() > before, "الجدولة مزاحة إلى المستقبل")
        assertEquals(0, app.testNotificationManager().activeNotifications.size, "الإشعار يُخفى (A-N09)")
    }

    @Test
    fun remindTomorrowWhenKoinUnavailableStillClearsNotificationWithoutCrashing() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)
        val alarmTriggerBefore = shadowOf(app.testAlarmManager()).scheduledAlarms.single().getTriggerAtMs()

        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_REMIND_TOMORROW,
            koinProvider = { null },
        )

        val alarmsAfter = shadowOf(app.testAlarmManager()).scheduledAlarms
        assertEquals(1, alarmsAfter.size)
        assertEquals(alarmTriggerBefore, alarmsAfter.single().getTriggerAtMs())
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }

    @Test
    fun cleanupFailureInFinallyDoesNotCrashOrMaskCancellation(): Unit = runBlocking {
        val throwingCleaner: (Context) -> Unit = { throw IllegalStateException("cleanup failed") }

        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_CALL_NOW,
            notificationCanceller = throwingCleaner,
        )
        assertNotNull(shadowOf(app).nextStartedActivity)

        val cancellingStart = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw CancellationException("simulated coroutine cancellation")
            }
        }
        assertFailsWith<CancellationException> {
            ArhamNotificationActionReceiver().runAction(
                appContext = cancellingStart,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
                notificationCanceller = throwingCleaner,
            )
        }
        Unit
    }
}
