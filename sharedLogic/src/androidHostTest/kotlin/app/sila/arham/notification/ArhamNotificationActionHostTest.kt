package app.sila.arham.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
 * اختبارات انحدار A-N08 لخط إشعارات أرحام فوق Robolectric وKoin الحقيقي
 * (`createSilaSharedModule`): زر «اتصل الآن» يطلق النشاط مباشرةً من النظام
 * (`PendingIntent.getActivity` — نمط نقرة المحتوى المثبت A-N07) لأن البثّ عبر
 * المستقبِل ثم `startActivity` من مكوّنٍ خلفي تُجهاضه سياسة بدء-الخلفية دائماً
 * حتى بحالة التطبيق الطازجة (`-91` على المحاكي وANFE على MIUI). وتغطي:
 * نوع الـ PendingIntent وهويته (2002) وتوافق Android 12، والإطلاق الفعلي عند
 * الإرسال (لا يكفي أن يكون غير فارغ)، بقاء «ذكّرني غداً» بثّاً (A-N09)، عدم
 * التداخل مع نقرة المحتوى (A-N07)، والتنظيف في `finally` بعد النجاح والفشل،
 * ولا PII في أي Intent ولا وجهة اتصال (لا مكالمة).
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

    // ===== أدوات =====

    private fun notificationManager() =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun alarmManager() =
        app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private suspend fun seedOverdueContact(displayName: String = "عم التأخير") {
        koin.get<ContactDao>().upsertContact(
            Contact(
                id = 0L,
                systemContactId = "t8_an08_fixture",
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

    /** يرصد إشعار التذكير الفعلي عبر خط الإطلاق القياسي (جهة اصطناعية بلا PII). */
    private suspend fun postDigestNotification() {
        seedOverdueContact()
        ArhamReminderDispatch.fireDailyReminder(
            context = app,
            preferences = koin.get(),
            planner = koin.get(),
            scheduler = koin.get(),
            notificationsEnabled = true,
        )
    }

    // ===== نوع الفعل وهويته: نشاط مباشر بمكوّن صريح يتجاوز MATCH_DEFAULT_ONLY =====

    @Test
    fun callNowActionIsADirectImmutableActivityPendingIntentToTheLauncher() = runBlocking {
        postDigestNotification()
        val notification = notificationManager().activeNotifications.single().notification
        val callNow = shadowOf(notification.actions[0].actionIntent)

        assertTrue(
            callNow.isActivity(),
            "«اتصل الآن» يطلق النشاط مباشرةً — لا بثّ عبر المستقبِل (بثّ المستقبِل يُجهاض دائماً)",
        )
        assertEquals(2002, callNow.requestCode, "هوية الطلب 2002 محفوظة")
        assertTrue(callNow.isImmutable(), "توافق Android 12: FLAG_IMMUTABLE")

        val expectedComponent = ComponentName(
            app.packageName,
            ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME,
        )
        val intent = callNow.getSavedIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, intent.`package`)
        assertEquals(
            expectedComponent,
            intent.component,
            "المكوّن الصريح مطلوب لأن MATCH_DEFAULT_ONLY يفشل مع نية MAIN/LAUNCHER الضمنية (aInfo is null / -91)",
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.data, "بلا وجهة اتصال — فتح التطبيق حصراً (لا مكالمة)")
        assertEquals(0, intent.extras?.size() ?: 0, "بلا PII في أي Intent")

        // الهوية انتقلت: لم يبقَ بثّ بحساب 2002 (النمط القديم يزول تماماً)
        val legacyBroadcast = PendingIntent.getBroadcast(
            app,
            2002,
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
        val mainComponent = ComponentName(
            app.packageName,
            ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME,
        )
        val activityInfo = ActivityInfo().apply {
            packageName = app.packageName
            name = mainComponent.className
            exported = true
        }
        val launcherFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        shadowOf(app.packageManager).apply {
            addOrUpdateActivity(activityInfo)
            addIntentFilterForActivity(mainComponent, launcherFilter)
        }

        // النية الضمنية القديمة (بلا مكوّن وبلا CATEGORY_DEFAULT في المانيفست)
        // ترجع null تحت MATCH_DEFAULT_ONLY الذي يفرضه ActivityTaskSupervisor دائماً.
        val legacyImplicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNull(
            app.packageManager.resolveActivity(legacyImplicit, PackageManager.MATCH_DEFAULT_ONLY),
            "النية الضمنية بلا CATEGORY_DEFAULT تعطي null (سبب aInfo is null و -91)",
        )

        postDigestNotification()
        val notification = notificationManager().activeNotifications.single().notification
        val callNowIntent = shadowOf(notification.actions[0].actionIntent).getSavedIntent()
        val contentIntent = shadowOf(notification.contentIntent).getSavedIntent()

        val callResolved = app.packageManager.resolveActivity(
            callNowIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val contentResolved = app.packageManager.resolveActivity(
            contentIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertEquals(mainComponent.className, callResolved?.activityInfo?.name)
        assertEquals(mainComponent.className, contentResolved?.activityInfo?.name)
    }

    // ===== الإطلاق الفعلي: send() يبدأ نشاط المقدمة حصراً =====

    @Test
    fun callNowActionActuallyStartsTheLauncherActivityWhenSent() = runBlocking {
        postDigestNotification()
        val callNow = notificationManager().activeNotifications.single().notification.actions[0]
            .actionIntent
        assertTrue(shadowOf(callNow).isActivity(), "المقدمة: النية نشاط إطلاقاً (A-N08)")

        // النظام يستدعي send() فعلياً عند النقر (ما يفعله SystemUI) — الإطلاق الفعلي
        // مطلوب في الاختبار: لا يكفي أن يكون PendingIntent غير فارغ.
        callNow.send()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started, "الإطلاق الفعلي: نشاط بدأ فعلًا")
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, started.`package`)
        assertEquals(
            ComponentName(app.packageName, ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME),
            started.component,
        )
    }

    // ===== لا تداخل: «ذكّرني غداً» يبقى بثّاً (A-N09) ونقرة المحتوى كما هي (A-N07) =====

    @Test
    fun remindTomorrowStaysABroadcastToTheActionReceiver() = runBlocking {
        postDigestNotification()
        val notification = notificationManager().activeNotifications.single().notification
        val remind = shadowOf(notification.actions[1].actionIntent)

        assertTrue(remind.isBroadcast(), "«ذكّرني غداً» يبقى بثّاً — يعمل على المنصتين (A-N09)")
        assertEquals(2003, remind.requestCode)
        assertTrue(remind.isImmutable(), "توافق Android 12: FLAG_IMMUTABLE")

        val intent = remind.getSavedIntent()
        assertEquals(ArhamNotificationPoster.ACTION_REMIND_TOMORROW, intent.action)
        assertEquals(
            ArhamNotificationActionReceiver::class.java.name,
            intent.component?.className,
            "بثّ صريح المكوّن كما هو",
        )
        assertEquals(0, intent.extras?.size() ?: 0, "بلا extras (لا PII)")
    }

    @Test
    fun contentTapStaysTheProvenLauncherActivityPendingIntent() = runBlocking {
        postDigestNotification()
        val content = shadowOf(
            notificationManager().activeNotifications.single().notification.contentIntent,
        )

        assertTrue(content.isActivity(), "نقرة المحتوى كما هي — لا تداخل مع A-N07")
        assertEquals(2004, content.requestCode)
        assertTrue(content.isImmutable(), "توافق Android 12: FLAG_IMMUTABLE")

        val intent = content.getSavedIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(app.packageName, intent.`package`)
        assertEquals(
            ComponentName(app.packageName, ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME),
            intent.component,
        )
        assertEquals(0, intent.extras?.size() ?: 0, "بلا extras (لا PII)")
    }

    // ===== التنظيف في finally: بعد النجاح والفشل على السواء =====

    @Test
    fun notificationIsClearedEvenWhenTheActionHandlerFails() = runBlocking {
        postDigestNotification()
        assertEquals(1, notificationManager().activeNotifications.size)

        // محاكاة إجهاض بدء-النشاط (ما تفعله المنصة فعلياً في مسار البثّ القديم):
        // سياق يرمي ANFE عند startActivity، والمسح يجب أن يبقى مضموناً.
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
            notificationManager().activeNotifications.size,
            "التنظيف في finally: الإشعار يُخفى حتى عند الرمي",
        )
    }

    @Test
    fun notificationIsClearedAndAppOpensAfterASuccessfulAction() = runBlocking {
        postDigestNotification()
        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_CALL_NOW,
        )

        assertNotNull(shadowOf(app).nextStartedActivity, "التطبيق فُتح (المسار الاحتياطي يظل يعمل)")
        assertEquals(0, notificationManager().activeNotifications.size, "التنظيف بعد النجاح كذلك")
    }

    @Test
    fun remindTomorrowActionReschedulesOneFutureAlarmAndClearsTheNotification() = runBlocking {
        postDigestNotification()
        val before = System.currentTimeMillis()
        ArhamNotificationActionReceiver().runAction(
            appContext = app,
            action = ArhamNotificationPoster.ACTION_REMIND_TOMORROW,
        )

        val alarms = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, alarms.size, "لا تذكيرات مكررة — إعادة الجدولة تستبدل (A-N10)")
        assertTrue(alarms.single().getTriggerAtMs() > before, "الجدولة مزاحة إلى المستقبل")
        assertEquals(0, notificationManager().activeNotifications.size, "الإشعار يُخفى (A-N09)")
    }
}
