package app.sila.feature.contacts

import app.sila.arham.notification.ArhamAlarmScheduler
import app.sila.arham.notification.IosArhamNotificationCenterRegistrar
import app.sila.arham.notification.IosArhamNotificationCenterRegistering
import app.sila.arham.notification.IosArhamNotificationScheduler
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.observer.IosCallStateObserver
import app.sila.arham.emergency.IosEmergencyCallPlacer
import app.sila.arham.emergency.IosEmergencyRingingController
import app.sila.arham.widget.IosArhamWidgetSnapshotPublisher
import app.sila.core.database.dao.ArhamDataChangeListener
import app.sila.di.createSilaSharedModule
import app.sila.feature.arham.domain.EmergencyRingingController
import app.sila.feature.arham.presentation.ArhamScreeningConsentSource
import app.sila.feature.contacts.data.IosContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsPlatformSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * اختبار دخان لربط Koin (Issue #22 — بند «التحقق من الربط في اختبار دخان»):
 * يُحلّ [ContactsPlatformSource] من وحدة المشتركة الكاملة [`createSilaSharedModule`]
 * — نفس المسار الذي تقرأ منه أدوات المزامنة التزايدية (ADR-0010 §7) — على iOS بلا
 * سياق (الـ actual لنظام iOS بلا تبعيات، وتعريفات Koin كلها حثيفة التحميل).
 */
class KoinWiringSmokeTest {

    /**
     * عزل Koin العام على K/N: عدّاء الاختبارات عمليةٌ واحدة تشغّل الأصناف كلها بالتتابع
     * (بخلاف JVM الذي يجري كل صنف في عملية نظيفة). صنفٌ سابق قد يترك السياق العام
     * مشدوداً (خاصة عند إخفاق initKoin بعد ضبط السياق) — فيرمي `startKoin`
     * `KoinApplicationAlreadyStartedException`. إعادة الضبط قبلها وبعدها تجعل الصنف
     * مستقلاً عن ترتيب التنفيذ.
     */
    @BeforeTest
    fun setUp() {
        stopKoin()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun sharedModule_resolvesContactsPlatformSource() {
        val app = startKoin { modules(createSilaSharedModule(storageDirPath = null)) }
        try {
            val resolved = app.koin.get<ContactsPlatformSource>()
            assertTrue(resolved is IosContactsPlatformSource, "iOS actual must resolve from the shared module graph.")
        } finally {
            app.close()
        }
    }

    /**
     * T8-12/T8-13: عقود أرحام المنصّية تُحلّ من الرسم الكامل نفسه — الجدولة بتنفيذ
     * `UNUserNotificationCenter`، والمراقبة بجسر `CXCallObserver`، والمندوب مقيَّد
     * بالرسم، ومصدر موافقة الفرز صامتٌ يعيد `null` (لا فرز على iOS — إخفاء البطاقة)،
     * وحلقة الطوارئ تنفّذ `tel:` مع اسمٍ مستعار لإعادة الاتصال، وناشر لقطة الودجت
     * مستمعُ تغيّر البيانات (D6). كلها حثيفة التحميل: الحل لا يلمس مركز الإشعارات
     * ولا `CXCallObserver` إطلاقاً.
     */
    @Test
    fun sharedModule_resolvesArhamPlatformContracts() {
        val app = startKoin { modules(createSilaSharedModule(storageDirPath = null)) }
        try {
            val scheduler = app.koin.get<ArhamAlarmScheduler>()
            assertTrue(scheduler is IosArhamNotificationScheduler, "ArhamAlarmScheduler must resolve to the iOS notification scheduler.")

            val observer = app.koin.get<ArhamCallStateObserver>()
            assertTrue(observer is IosCallStateObserver, "ArhamCallStateObserver must resolve to the CXCallObserver bridge.")

            val registering = app.koin.get<IosArhamNotificationCenterRegistering>()
            assertTrue(
                registering is IosArhamNotificationCenterRegistrar,
                "The notification-center delegate must be registered in the graph via its pure-Kotlin registrar.",
            )

            val consent = app.koin.get<ArhamScreeningConsentSource>()
            assertTrue(
                consent.isRoleHeld() == null,
                "iOS has no screening role — the silent source must answer null to hide the consent card.",
            )

            // T8-13: حلقة الطوارئ بتنفيذ `tel:`، وإعادة الاتصال اليدوية اسمٌ لنفس المثيل،
            // وناشر لقطة الودجت (D6) هو مستمع تغيّر البيانات المُسجَّل على قاعدة البيانات.
            val emergency = app.koin.get<EmergencyRingingController>()
            assertTrue(
                emergency is IosEmergencyRingingController,
                "EmergencyRingingController must resolve to the iOS tel:-dial controller.",
            )

            val placer = app.koin.get<IosEmergencyCallPlacer>()
            assertSame(
                emergency,
                placer,
                "IosEmergencyCallPlacer must alias the very same controller instance (one Room reader, one dialer).",
            )

            val widgetListener = app.koin.get<ArhamDataChangeListener>()
            assertTrue(
                widgetListener is IosArhamWidgetSnapshotPublisher,
                "ArhamDataChangeListener must resolve to the App-Group snapshot publisher.",
            )
            assertSame(
                widgetListener,
                app.koin.get<IosArhamWidgetSnapshotPublisher>(),
                "The listener alias must be the same publisher instance the bootstrap registers.",
            )
        } finally {
            app.close()
        }
    }
}
