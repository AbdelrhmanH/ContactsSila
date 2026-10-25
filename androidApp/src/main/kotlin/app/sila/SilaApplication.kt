package app.sila

import android.app.Application
import app.sila.di.ensureKoinStarted

/**
 * نقطة تهيئة التطبيق على Android — تضمن بدء Koin مرة واحدة على مستوى العملية (Process)
 * بحيث لا يُعاد فتح SQLite أو DataStore عند تدوير الشاشة.
 */
class SilaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureKoinStarted(storageDirPath = filesDir.absolutePath)
    }
}
