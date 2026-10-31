package app.sila

import android.app.Application
import app.sila.di.ensureKoinStarted
import app.sila.feature.contacts.data.ContactsSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin

/**
 * نقطة تهيئة التطبيق على Android — تضمن بدء Koin مرة واحدة على مستوى العملية (Process)
 * بحيث لا يُعاد فتح SQLite أو DataStore عند تدوير الشاشة، ثم تشغيل محرك المزامنة
 * التزايدية لجهات الاتصال على نطاق عمر التطبيق (Issue #23 · ADR-0010).
 */
class SilaApplication : Application() {

    /** نطاق عمر التطبيق لمهام الخلفية طويلة العمر (مراقبة الصلاحيات/الإشارات). */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engineStarted = false

    override fun onCreate() {
        super.onCreate()
        val koin = ensureKoinStarted(storageDirPath = filesDir.absolutePath) {
            androidContext(this@SilaApplication)
        }
        startContactsSync(koin)
    }

    /** تشغيل محرك المزامنة مرة واحدة (idempotent — onCreate قد يُستدعى على عملية جديدة فقط). */
    private fun startContactsSync(koin: Koin) {
        if (engineStarted) return
        engineStarted = true
        koin.get<ContactsSyncEngine>().start(applicationScope)
    }
}
