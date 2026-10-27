package app.sila.di

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.main.MainViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * مهيئ حاوية Koin لمنصة iOS (Issue #5 & Issue #9).
 *
 * يلتزم باستخدام مجلد `NSApplicationSupportDirectory` (`Application Support/Sila`)
 * لتخزين قاعدة بيانات Room KMP (`sila_local.db`) وملف تفضيلات DataStore
 * (`sila_user_preferences.preferences_pb`) على القرص، مع منع السقوط الصامت إلى `:memory:`.
 */
object IosKoinBootstrap {

    private const val APP_SUPPORT_SUBDIRECTORY = "Sila"

    /**
     * يحسم المسار المطلق لمجلد `Application Support/Sila` وينشئه على القرص إن لم يكن موجوداً.
     * يرمي [IllegalStateException] فوراً إذا تعذر الوصول للمجلد أو إنشاؤه (Fail-Fast).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun resolveApplicationSupportPath(): String {
        val basePath = NSSearchPathForDirectoriesInDomains(
            directory = NSApplicationSupportDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        ).firstOrNull() as? String

        check(!basePath.isNullOrBlank()) {
            "Unable to resolve iOS NSApplicationSupportDirectory in NSUserDomainMask."
        }

        val normalizedBase = basePath.trimEnd('/')
        val appSupportDir = "$normalizedBase/$APP_SUPPORT_SUBDIRECTORY"
        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(appSupportDir)) {
            val created = fileManager.createDirectoryAtPath(
                path = appSupportDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
            check(created) {
                "Failed to create iOS Application Support directory at path: $appSupportDir"
            }
        }

        check(fileManager.fileExistsAtPath(appSupportDir)) {
            "iOS Application Support directory does not exist after creation check: $appSupportDir"
        }
        check(appSupportDir != ":memory:") {
            "iOS Application Support path must never be in-memory (:memory:)."
        }

        return appSupportDir
    }

    /**
     * يضمن بدء تشغيل Koin بمسار `Application Support/Sila` الفعلي على القرص بشكل Idempotent،
     * ويتحقق من عدم استخدام التخزين المؤقت في الذاكرة (`:memory:`).
     */
    fun ensureStarted(): Koin {
        val storageDirPath = resolveApplicationSupportPath()
        val expectedDbPath = "$storageDirPath/sila_local.db"
        val expectedPrefsPath = "$storageDirPath/${SilaPreferencesDataSource.DATASTORE_FILE_NAME}"

        val existingKoin = KoinPlatformTools.defaultContext().getOrNull()
        if (existingKoin != null) {
            val existingDb = existingKoin.get<LocalSilaDatabase>()
            val existingPrefs = existingKoin.get<UserPreferencesDataSource>() as? SilaPreferencesDataSource
            if (existingDb.dbFilePath == expectedDbPath && existingPrefs?.storageFilePath == expectedPrefsPath) {
                return existingKoin
            }
            stopKoin()
        }

        val koin = ensureKoinStarted(storageDirPath = storageDirPath)
        val db = koin.get<LocalSilaDatabase>()
        val prefs = koin.get<UserPreferencesDataSource>() as? SilaPreferencesDataSource

        check(db.dbFilePath != ":memory:" && db.dbFilePath == expectedDbPath) {
            "IosKoinBootstrap persistence check failed: expected SQLite path '$expectedDbPath', but got '${db.dbFilePath}'."
        }
        check(prefs != null && !prefs.storageFilePath.isNullOrBlank() && prefs.storageFilePath == expectedPrefsPath) {
            "IosKoinBootstrap persistence check failed: expected DataStore path '$expectedPrefsPath', but got '${prefs?.storageFilePath}'."
        }

        return koin
    }

    /**
     * يسترجع [MainViewModel] المشترك بعد ضمان تهيئة Koin بمسار التخزين الدائم.
     */
    fun getMainViewModel(): MainViewModel = ensureStarted().get()
}
