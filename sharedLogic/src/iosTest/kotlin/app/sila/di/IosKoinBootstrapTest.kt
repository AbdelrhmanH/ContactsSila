package app.sila.di

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.DefaultTab
import app.sila.feature.main.MainIntent
import app.sila.maintenance.ArhamMaterializationCoordinator
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import platform.Foundation.NSFileManager
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosKoinBootstrapTest {

    @BeforeTest
    fun setUp() {
        stopKoin()
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.resetArhamLifecycleFlagsForTest()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun resolveApplicationSupportPath_createsAndReturnsApplicationSupportDirectory() {
        val path = IosKoinBootstrap.resolveApplicationSupportPath()

        assertTrue(path.isNotBlank(), "Resolved Application Support path must not be blank.")
        assertNotEquals(":memory:", path, "Resolved path must never be ':memory:'.")
        assertTrue(
            path.contains("Application Support/Sila"),
            "Resolved path '$path' must reside under 'Application Support/Sila'."
        )
        assertFalse(
            path.contains("/Documents"),
            "Resolved path '$path' must use Application Support, not NSDocumentDirectory."
        )
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "Resolved Application Support directory '$path' must exist on disk."
        )
    }

    @Test
    fun ensureStarted_isIdempotentAndConfiguresOnDiskPersistence() {
        val supportPath = IosKoinBootstrap.resolveApplicationSupportPath()
        val koinFirst = IosKoinBootstrap.ensureStarted()
        val koinSecond = IosKoinBootstrap.ensureStarted()

        assertSame(koinFirst, koinSecond, "Repeated ensureStarted() calls must return the same Koin instance.")

        val db = koinFirst.get<LocalSilaDatabase>()
        val prefs = koinFirst.get<UserPreferencesDataSource>() as? SilaPreferencesDataSource

        assertNotEquals(":memory:", db.dbFilePath, "SQLite database must not use in-memory storage.")
        assertEquals("$supportPath/sila_local.db", db.dbFilePath)
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(db.dbFilePath),
            "SQLite database file '${db.dbFilePath}' must be created on disk."
        )

        assertNotNull(prefs, "UserPreferencesDataSource must be backed by SilaPreferencesDataSource.")
        assertEquals(
            "$supportPath/${SilaPreferencesDataSource.DATASTORE_FILE_NAME}",
            prefs.storageFilePath
        )
    }

    @Test
    fun ensureStarted_replacesInMemoryContextAndNeverSilentlyFallsBackToMemory() {
        // Simulate an accidental prior in-memory Koin initialization
        val inMemoryKoin = ensureKoinStarted(storageDirPath = null)
        assertEquals(":memory:", inMemoryKoin.get<LocalSilaDatabase>().dbFilePath)

        // IosKoinBootstrap.ensureStarted() must replace the in-memory container with on-disk Application Support storage
        val diskKoin = IosKoinBootstrap.ensureStarted()
        val db = diskKoin.get<LocalSilaDatabase>()

        assertNotEquals(":memory:", db.dbFilePath, "IosKoinBootstrap must never silently retain in-memory storage.")
        assertTrue(db.dbFilePath.endsWith("/Application Support/Sila/sila_local.db"))
    }

    @Test
    fun getMainViewModel_resolvesViewModelAndDispatchesIntents() = runTest {
        val vm = IosKoinBootstrap.getMainViewModel()
        assertNotNull(vm)

        assertEquals(DefaultTab.CONTACTS, vm.state.value.activeTab)
        vm.onIntent(MainIntent.SelectTab(DefaultTab.ARHAM))
        assertEquals(DefaultTab.ARHAM, vm.state.value.activeTab)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun ensureStarted_launchesRetentionCleanupCoordinator_andPublishesReport() {
        // F-02: مسار الإنتاج على iOS يشغّل صيانة الاستبقاء من كلا مساري ensureStarted —
        // التنظيف مرة واحدة لكل عملية، والتقرير يُنشر على lastReport بعد نجاح المعاملة.
        // علم الإطلاق الواحد على مستوى الكائن قد استُهلك باختبار سابق (stopKoin بين
        // الاختبارات يُنشئ حاوية جديدة بعلمٍ قديم) — إعادة تعيينه هنا تجعل الاختبار
        // مستقلاً عن ترتيب التنفيذ داخل الملف.
        IosKoinBootstrap.resetCleanupLaunchForTest()
        val koin = IosKoinBootstrap.ensureStarted()
        val coordinator = koin.get<RetentionCleanupCoordinator>()

        // الانتظار على الزمن الحقيقي حصراً (وليس الزمن الافتراضي لـ runTest): التنظيف
        // ينفَّذ على خيوط إنتاجية حقيقية (engineScope + Dispatchers.io في الـ DAO)
        // خارج نطاق الاختبار — لذا استطلاع دوري بسقف زمني 15 ثانية.
        var waitedMillis = 0L
        while (coordinator.lastReport.value == null && waitedMillis < 15_000L) {
            usleep(100_000u) // 100ms
            waitedMillis += 100L
        }

        assertNotNull(
            coordinator.lastReport.value,
            "Retention cleanup must execute from IosKoinBootstrap.ensureStarted() and publish its report."
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun ensureStarted_launchesTopFiveMaterialization_andPublishesOutcome() {
        // T8-15 (F-1): مسار الإنتاج على iOS يشغّل تجسيد «أهم 5» من كلا مساري ensureStarted —
        // الحاوية الطازجة بلا قائمة معلّقة فيكون NothingPending أول نتيجة منشورة (لا كتابة —
        // حارس §F.11-هـ داخل الحالة الاستخدامية).
        IosKoinBootstrap.resetMaterializationForTest()
        val koin = IosKoinBootstrap.ensureStarted()
        val coordinator = koin.get<ArhamMaterializationCoordinator>()

        // الانتظار على الزمن الحقيقي حصراً (وليس الزمن الافتراضي لـ runTest): التجسيد
        // ينفَّذ على خيوط إنتاجية حقيقية (engineScope) خارج نطاق الاختبار — لذا استطلاع
        // دوري بسقف زمني 15 ثانية (مرآة اختبار الاستبقاء أعلاه).
        var waitedMillis = 0L
        while (coordinator.lastOutcome.value == null && waitedMillis < 15_000L) {
            usleep(100_000u) // 100ms
            waitedMillis += 100L
        }

        assertNotNull(
            coordinator.lastOutcome.value,
            "Top-five materialization must execute from IosKoinBootstrap.ensureStarted() and publish its outcome."
        )
    }
}
