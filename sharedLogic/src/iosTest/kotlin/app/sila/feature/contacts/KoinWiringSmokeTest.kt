package app.sila.feature.contacts

import app.sila.di.createSilaSharedModule
import app.sila.feature.contacts.data.IosContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsPlatformSource
import org.koin.core.context.startKoin
import kotlin.test.Test
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
}
