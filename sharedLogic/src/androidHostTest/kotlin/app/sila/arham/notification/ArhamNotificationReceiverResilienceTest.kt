package app.sila.arham.notification

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import app.sila.core.common.AppDispatchers
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun foreignAndNullActionsInOnReceiveAreIgnoredSynchronouslyWithoutCancellingNotification() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        val receiver = ArhamNotificationActionReceiver()
        assertNull(receiver.dispatchAction(app, Intent("com.example.FOREIGN_ACTION")))
        assertNull(receiver.dispatchAction(app, Intent()))
        receiver.onReceive(app, Intent("com.example.FOREIGN_ACTION"))
        receiver.onReceive(app, Intent())

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(
            1,
            app.testNotificationManager().activeNotifications.size,
            "الفعل الغريب يُتجاهل فوراً قبل goAsync() ولا يلغي إشعار 2001",
        )
    }

    @Test
    fun dispatchActionRemindTomorrowEndToEndThroughReceiverAndClearsNotification() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)
        val before = System.currentTimeMillis()
        val testThreadDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val receiver = ArhamNotificationActionReceiver(
            dispatcherOverride = testThreadDispatcher,
            koinProvider = { koin },
        )

        val job = receiver.dispatchAction(
            app,
            Intent(app, ArhamNotificationActionReceiver::class.java)
                .setAction(ArhamNotificationPoster.ACTION_REMIND_TOMORROW),
        )
        assertNotNull(job)
        job.join()

        val alarms = shadowOf(app.testAlarmManager()).scheduledAlarms
        assertEquals(1, alarms.size)
        assertTrue(alarms.single().getTriggerAtMs() > before)
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }

    @Test
    fun onReceiveDispatchesCallNowEndToEndThroughReceiverAndClearsNotification() {
        runBlocking { postDigestNotification(app, koin) }
        assertEquals(1, app.testNotificationManager().activeNotifications.size)
        val receiver = ArhamNotificationActionReceiver(
            dispatcherOverride = Dispatchers.Unconfined,
        )

        receiver.onReceive(
            app,
            Intent(ArhamNotificationPoster.ACTION_CALL_NOW),
        )

        assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }

    @Test
    fun cancellationDuringRemindTomorrowReschedulePropagatesAndClearsNotification() = runBlocking {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        val receiver = ArhamNotificationActionReceiver(
            koinProvider = { throw CancellationException("cancelled during reschedule") },
        )
        assertFailsWith<CancellationException> {
            receiver.runAction(
                appContext = app,
                action = ArhamNotificationPoster.ACTION_REMIND_TOMORROW,
            )
        }
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
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

        ArhamNotificationActionReceiver(koinProvider = { null }).runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_REMIND_TOMORROW,
        )

        val alarmsAfter = shadowOf(app.testAlarmManager()).scheduledAlarms
        assertEquals(1, alarmsAfter.size)
        assertEquals(alarmTriggerBefore, alarmsAfter.single().getTriggerAtMs())
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }

    @Test
    fun cleanupFailureInFinallyDoesNotCrashOrMaskCancellation() = runBlocking<Unit> {
        val throwingCleaner: (Context) -> Unit = { throw IllegalStateException("cleanup failed") }

        ArhamNotificationActionReceiver(notificationCanceller = throwingCleaner).runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_CALL_NOW,
        )
        assertNotNull(shadowOf(app).nextStartedActivity)

        val cancellingStart = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw CancellationException("simulated coroutine cancellation")
            }
        }
        assertFailsWith<CancellationException> {
            ArhamNotificationActionReceiver(notificationCanceller = throwingCleaner).runAction(
                appContext = cancellingStart,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
            )
        }
    }

    @Test
    fun errorDuringActionAndCancellationInCleanupFinallyPropagateWithoutBeingSwallowed() = runBlocking<Unit> {
        postDigestNotification(app, koin)
        assertEquals(1, app.testNotificationManager().activeNotifications.size)

        val errorStart = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw AssertionError("fatal action error")
            }
        }
        assertFailsWith<AssertionError> {
            ArhamNotificationActionReceiver().runAction(
                appContext = errorStart,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
            )
        }
        assertEquals(0, app.testNotificationManager().activeNotifications.size)

        val cancellingCleaner: (Context) -> Unit = {
            throw CancellationException("cancelled in finally")
        }
        assertFailsWith<CancellationException> {
            ArhamNotificationActionReceiver(notificationCanceller = cancellingCleaner).runAction(
                appContext = app,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
            )
        }

        val errorCleaner: (Context) -> Unit = { throw AssertionError("fatal cleanup error") }
        assertFailsWith<AssertionError> {
            ArhamNotificationActionReceiver(notificationCanceller = errorCleaner).runAction(
                appContext = app,
                action = ArhamNotificationPoster.ACTION_CALL_NOW,
            )
        }
    }

    @Test
    fun dispatchActionWithNullOverrideUsesKoinAppDispatchersAndHandlesFallbackAndCancellation() = runBlocking<Unit> {
        postDigestNotification(app, koin)
        val testThreadDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        var lookups = 0
        var ioUsed = false
        val trackingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                ioUsed = true
                testThreadDispatcher.dispatch(context, block)
            }
        }
        koin.loadModules(
            listOf(
                module {
                    single<AppDispatchers> {
                        object : AppDispatchers {
                            override val main = testThreadDispatcher
                            override val io = trackingDispatcher
                            override val default = testThreadDispatcher
                        }
                    }
                },
            ),
            allowOverride = true,
        )

        val koinJob = ArhamNotificationActionReceiver(
            koinProvider = {
                lookups++
                koin
            },
        ).dispatchAction(app, Intent(ArhamNotificationPoster.ACTION_REMIND_TOMORROW))
        assertNotNull(koinJob)
        koinJob.join()
        assertEquals(1, lookups)
        assertTrue(ioUsed)
        assertEquals(0, app.testNotificationManager().activeNotifications.size)

        postDigestNotification(app, koin)
        val fallbackJob = ArhamNotificationActionReceiver(koinProvider = { null })
            .dispatchAction(app, Intent(ArhamNotificationPoster.ACTION_REMIND_TOMORROW))
        assertNotNull(fallbackJob)
        fallbackJob.join()
        assertEquals(0, app.testNotificationManager().activeNotifications.size)

        postDigestNotification(app, koin)
        val cancelJob = ArhamNotificationActionReceiver(
            koinProvider = { throw CancellationException("cancelled in lookup") },
        ).dispatchAction(app, Intent(ArhamNotificationPoster.ACTION_REMIND_TOMORROW))
        assertNotNull(cancelJob)
        cancelJob.join()
        assertTrue(cancelJob.isCancelled)
        assertEquals(0, app.testNotificationManager().activeNotifications.size)
    }
}
