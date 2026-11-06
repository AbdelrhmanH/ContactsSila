package app.sila.feature.contacts

import app.sila.arham.notification.ArhamAlarmScheduler
import app.sila.arham.notification.IosArhamNotificationCenterDelegate
import app.sila.arham.notification.IosArhamNotificationScheduler
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.observer.IosCallStateObserver
import app.sila.di.createSilaSharedModule
import app.sila.feature.arham.presentation.ArhamScreeningConsentSource
import app.sila.feature.contacts.data.IosContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsPlatformSource
import org.koin.core.context.startKoin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * اختبار دخان لربط Koin (Issue #22 — بند «التحقق من الربط في اختبار دخان»):
 * يُحلّ [ContactsPlatformSource] من وحدة المشتركة الكاملة [`createSilaSharedModule`]
 * — نفس المسار الذي تقرأ منه أدوات المزامنة التزايدية (ADR-0010 §7) — على iOS بلا
 * سياق (الـ actual لنظام iOS بلا تبعيات، وتعريفات Koin كلها حثيفة التحميل).
 */
class KoinWiringSmokeTest {

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
     * T8-12: عقود أرحام المنصّية تُحلّ من الرسم الكامل نفسه — الجدولة بتنفيذ
     * `UNUserNotificationCenter`، والمراقبة بجسر `CXCallObserver`، والمندوب مقيَّد
     * بالرسم، ومصدر موافقة الفرز صامتٌ يعيد `null` (لا فرز على iOS — إخفاء البطاقة).
     * كلها حثيفة التحميل: الحل لا يلمس مركز الإشعارات ولا `CXCallObserver` إطلاقاً.
     */
    @Test
    fun sharedModule_resolvesArhamPlatformContracts() {
        val app = startKoin { modules(createSilaSharedModule(storageDirPath = null)) }
        try {
            val scheduler = app.koin.get<ArhamAlarmScheduler>()
            assertTrue(scheduler is IosArhamNotificationScheduler, "ArhamAlarmScheduler must resolve to the iOS notification scheduler.")

            val observer = app.koin.get<ArhamCallStateObserver>()
            assertTrue(observer is IosCallStateObserver, "ArhamCallStateObserver must resolve to the CXCallObserver bridge.")

            val delegate = app.koin.get<IosArhamNotificationCenterDelegate>()
            assertNotNull(delegate, "The notification-center delegate must be registered in the graph.")

            val consent = app.koin.get<ArhamScreeningConsentSource>()
            assertTrue(
                consent.isRoleHeld() == null,
                "iOS has no screening role — the silent source must answer null to hide the consent card.",
            )
        } finally {
            app.close()
        }
    }
}
