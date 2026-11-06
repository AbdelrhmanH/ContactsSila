package app.sila.arham.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات مضيف أندرويد لخط إشعارات أرحام (T8-08 · D2 · §M) فوق Robolectric وKoin
 * الحقيقي (`createSilaSharedModule`): idempotency القناة، استبدال الإنذار (لا تراكم —
 * ميزانية ≤ 1/يوم)، غير الدقة وWAKEUP (`setAndAllowWhileIdle` — D2)، مسار الإذن
 * المرفوض (صمتٌ مع بقاء الجدولة)، ولا PII في أي Intent (خطر DeepSeek 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArhamNotificationHostTest {

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

    // ===== أدوات =====

    private fun notificationManager() =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun alarmManager() =
        app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun dispatch() = ArhamReminderDispatch

    private suspend fun seedOverdueContact(displayName: String = "عم التأخير") {
        koin.get<ContactDao>().upsertContact(
            Contact(
                id = 0L,
                systemContactId = "t8_08_fixture",
                name = ContactName(displayName = displayName),
                phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
                category = Category.FAMILY,
                arham = ArhamInfo(
                    relation = Relation.UNCLE_PATERNAL,
                    frequency = ArhamFrequency.WEEKLY,
                    lastContactedAt = System.currentTimeMillis() - 30L * 86_400_000L,
                ),
            )
        )
    }

    // ===== القناة =====

    @Test
    fun `channel ensure is idempotent`() {
        ArhamNotificationChannel.ensure(app)
        ArhamNotificationChannel.ensure(app)
        val channels = notificationManager().notificationChannels
        assertEquals(1, channels.size, "ضمان القناة مرتين ⇒ قناة واحدة (لا تكرار)")
        assertEquals(ArhamNotificationChannel.CHANNEL_ID, channels[0].id)
    }

    // ===== الجدولة (D2: غير دقيقة، WAKEUP، استبدال لا تراكم) =====

    @Test
    fun `scheduler is real android implementation when context registered`() {
        val scheduler = koin.get<ArhamAlarmScheduler>()
        assertTrue(scheduler is AndroidAlarmScheduler, "Context مسجَّل ⇒ الجدولة الحقيقية لا الصامتة")
    }

    @Test
    fun `rescheduling replaces the single alarm instead of accumulating`() {
        val scheduler = koin.get<ArhamAlarmScheduler>()
        val now = System.currentTimeMillis()
        scheduler.scheduleAt(now + 3_600_000L)
        scheduler.scheduleAt(now + 7_200_000L)

        val alarms = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, alarms.size, "ميزانية ≤ 1/يوم: نفس الـ PendingIntent يستبدل (§M)")
        val alarm = alarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType(), "WAKEUP — يوقظ الجهاز ضمن قيود Doze")
        assertTrue(alarm.isAllowWhileIdle(), "setAndAllowWhileIdle — علامة السماح داخل Doze (D2)")
        assertEquals(now + 7_200_000L, alarm.getTriggerAtMs(), "الموعد الأخير يستبدل الأول")
    }

    @Test
    fun `dispatch reschedule is idempotent and intent carries no PII`() = runBlocking {
        val now = System.currentTimeMillis()
        dispatch().rescheduleFromNow(
            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
        )
        dispatch().rescheduleFromNow(
            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
        )

        val alarms = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, alarms.size, "إعادة الجدولة من مسارين تستبدل لا تتراكم")
        val alarm = alarms.single()
        assertTrue(alarm.getTriggerAtMs() > now, "الموعد المحسوب مستقبلي دائماً")

        val savedIntent = shadowOf(alarm.operation).getSavedIntent()
        assertEquals(ArhamReminderDispatch.ACTION_DAILY_REMINDER, savedIntent.action)
        assertNull(savedIntent.extras, "لا PII في الـ Intent — النوع فقط (خطر DeepSeek 3 مُحسوم)")
    }

    // ===== الإطلاق: الإذن المرفوض يصمت والجدولة تبقى، والمسموح يظهر الرسالة المجمعة =====

    @Test
    fun `denied notifications suppress posting but keep the schedule alive`() = runBlocking {
        seedOverdueContact()
        dispatch().fireDailyReminder(            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
            notificationsEnabled = false,
        )
        assertEquals(0, notificationManager().activeNotifications.size, "مرفوض ⇒ لا إشعار")
        assertEquals(1, shadowOf(alarmManager()).scheduledAlarms.size, "ومع ذلك تبقى الجدولة حيّة")
    }

    @Test
    fun `enabled dispatch posts digest notification on its channel`() = runBlocking {
        seedOverdueContact()
        dispatch().fireDailyReminder(
            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
            notificationsEnabled = true,
        )
        val posted = notificationManager().activeNotifications
        assertEquals(1, posted.size, "متأخر واحد + إذن مسموح ⇒ إشعار واحد")
        val notification = posted[0].notification
        assertEquals(ArhamNotificationChannel.CHANNEL_ID, notification.channelId)
        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
        assertNotNull(text, "نص الإشعار من موارد الترجمة — يحمل اسم الظهور")
        assertTrue(text.toString().contains("عم التأخير"), "الرسالة المجمعة تحمل عنوان المتأخر الأول")
        assertEquals(1, shadowOf(alarmManager()).scheduledAlarms.size, "وبعد الإطلاق تُعاد الجدولة دائماً")
    }

    // ===== المستقبِلات: أفعال غريبة تُتجاهل بلا انهيار =====

    @Test
    fun `reminder receiver ignores foreign actions without crashing`() {
        ArhamReminderReceiver().onReceive(app, Intent("com.example.FOREIGN_ACTION"))
        assertEquals(0, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    @Test
    fun `boot receiver ignores foreign actions without crashing`() {
        ArhamBootReceiver().onReceive(app, Intent("com.example.FOREIGN_ACTION"))
        assertEquals(0, shadowOf(alarmManager()).scheduledAlarms.size)
    }
}
