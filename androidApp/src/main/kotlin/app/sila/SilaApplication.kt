package app.sila

import android.app.Application
import app.sila.arham.notification.ArhamNotificationChannel
import app.sila.arham.notification.ArhamReminderDispatch
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.di.ensureKoinStarted
import app.sila.feature.contacts.data.ContactsSyncEngine
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin

/**
 * نقطة تهيئة التطبيق على Android — تضمن بدء Koin مرة واحدة على مستوى العملية (Process)
 * بحيث لا يُعاد فتح SQLite أو DataStore عند تدوير الشاشة، ثم تشغيل محرك المزامنة
 * التزايدية لجهات الاتصال على نطاق عمر التطبيق (Issue #23 · ADR-0010)، وضمان قناة
 * إشعارات أرحام وإعادة جدولة تذكيرها اليومي (T8-08 · D2 — الموعد المفقود يُحسب
 * من `now` عند كل تشغيل عملية).
 */
class SilaApplication : Application() {

    /** نطاق عمر التطبيق لمهام الخلفية طويلة العمر (مراقبة الصلاحيات/الإشارات). */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engineStarted = false

    private var cleanupStarted = false

    private var reminderRescheduled = false

    private var callObserverStarted = false

    override fun onCreate() {
        super.onCreate()
        val koin = ensureKoinStarted(storageDirPath = filesDir.absolutePath) {
            androidContext(this@SilaApplication)
        }
        ArhamNotificationChannel.ensure(this)
        startContactsSync(koin)
        startRetentionCleanup(koin)
        rescheduleDailyReminder(koin)
        startCallStateObserver(koin)
    }

    /** تشغيل محرك المزامنة مرة واحدة (idempotent — onCreate قد يُستدعى على عملية جديدة فقط). */
    private fun startContactsSync(koin: Koin) {
        if (engineStarted) return
        engineStarted = true
        koin.get<ContactsSyncEngine>().start(applicationScope)
    }

    /**
     * F-02: تشغيل صيانة الاستبقاء (تنظيف القبور >30 يوماً والمكالمات >365 يوماً) مرة واحدة
     * لكل عملية على نطاق عمر التطبيق — خارج الخيط الرئيسي (التنفيذ داخل Dispatchers.io
     * في الـ DAO)، والإخفاق يُسجَّل بلا PII ولا يسقط التطبيق.
     */
    private fun startRetentionCleanup(koin: Koin) {
        if (cleanupStarted) return
        cleanupStarted = true
        koin.get<RetentionCleanupCoordinator>().launchIn(applicationScope)
    }

    /**
     * T8-08 (D2 §8): إعادة جدولة التذكير اليومي عند كل تشغيل عملية — الميزة معطّلة؟
     * لا شيء؛ وإلا خطةٌ جديدة من `now` (الموعد المفقود أثناء الغياب يُحسب من جديد،
     * ويُسلَّم بنهاية ساعات الهدوء إن كانت سارية). idempotent بمرآة `engineStarted`.
     */
    private fun rescheduleDailyReminder(koin: Koin) {
        if (reminderRescheduled) return
        reminderRescheduled = true
        applicationScope.launch {
            ArhamReminderDispatch.rescheduleFromNow(
                context = this@SilaApplication,
                preferences = koin.get(),
                planner = koin.get(),
                scheduler = koin.get(),
            )
        }
    }

    /**
     * T8-09 (AC-4/AC-17): مراقب حالة الهاتف — **حالة فقط بلا هوية**؛ بدايته idempotent
     * وبوابتُه صلاحية `READ_PHONE_STATE` حصراً (بلا مفتاح DataStore — قرار مثبت):
     * مرفوضة ⇒ لا تسجيل ولا عملية (سجلٌّ بنوعٍ حصراً داخل المراقب)، ومسار التسجيل
     * داخل التطبيق + التأكيد اليدوي (بديل AC-4) يعمل دوماً.
     */
    private fun startCallStateObserver(koin: Koin) {
        if (callObserverStarted) return
        callObserverStarted = true
        koin.get<ArhamCallStateObserver>().start(applicationScope)
    }
}
