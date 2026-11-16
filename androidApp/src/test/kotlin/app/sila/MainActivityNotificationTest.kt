package app.sila

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.sila.arham.notification.ArhamReminderDispatch
import app.sila.core.database.dao.ContactDao
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات ربط `MainActivity` الفعلي مع مسار إشعار «اتصل الآن» (`A-N08`):
 * فتح بارد يمسح إشعار 2001، إعادة الإنشاء (`savedInstanceState != null`) وإطلاق السجل
 * (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) لا يمسحانه، و`onNewIntent` على مثيل قائم يمسحه.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityNotificationTest {

    private val app get() = RuntimeEnvironment.getApplication()
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

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private suspend fun postReminderDigestAndGetCallNowIntent(): Intent {
        koin.get<ContactDao>().upsertContact(
            Contact(
                id = 0L,
                systemContactId = "main_activity_an08_fixture",
                name = ContactName(displayName = "عم الاختبار"),
                phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
                category = Category.FAMILY,
                arham = ArhamInfo(
                    relation = Relation.UNCLE_PATERNAL,
                    frequency = ArhamFrequency.WEEKLY,
                    lastContactedAt = System.currentTimeMillis() - 30L * 86_400_000L,
                ),
            ),
        )
        ArhamReminderDispatch.fireDailyReminder(
            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
            notificationsEnabled = true,
        )
        val notification = notificationManager().activeNotifications.single().notification
        return Intent(shadowOf(notification.actions[0].actionIntent).getSavedIntent())
    }

    @Test
    fun coldCreateAndOnNewIntentClearNotificationWhileRestoredAndHistoryLaunchesPreserveIt() = runBlocking {
        val coldIntent = postReminderDigestAndGetCallNowIntent()
        assertEquals(1, notificationManager().activeNotifications.size)

        val controller = Robolectric.buildActivity(MainActivity::class.java, coldIntent).create()
        assertEquals(0, notificationManager().activeNotifications.size)
        assertEquals(Intent.ACTION_MAIN, controller.get().intent.action)

        // إشعار جديد غير مقروء: إعادة إنشاء بـ savedInstanceState != null لا تمسح الإشعار.
        val recreatedIntent = postReminderDigestAndGetCallNowIntent()
        assertEquals(1, notificationManager().activeNotifications.size)
        Robolectric.buildActivity(MainActivity::class.java, recreatedIntent)
            .create(Bundle().apply { putBoolean("restored", true) })
        assertEquals(1, notificationManager().activeNotifications.size)

        // إطلاقه من قائمة التطبيقات الأخيرة (Recents) لا يمسح الإشعار غير المقروء أيضاً.
        val historyIntent = Intent(recreatedIntent)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        Robolectric.buildActivity(MainActivity::class.java, historyIntent).create()
        assertEquals(1, notificationManager().activeNotifications.size)

        // نقرة «اتصل الآن» جديدة تُسلَّم عبر onNewIntent وتمسح الإشعار وتُحدِّث نية النشاط.
        Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
            .create()
            .newIntent(recreatedIntent)
            .also { warmController ->
                assertEquals(Intent.ACTION_MAIN, warmController.get().intent.action)
            }
        assertEquals(0, notificationManager().activeNotifications.size)
    }
}
