package app.sila.di

import androidx.lifecycle.viewModelScope
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DefaultTab
import app.sila.feature.contacts.data.ContactsSyncEngine
import app.sila.feature.contacts.domain.ContactsSyncState
import app.sila.feature.main.MainIntent
import app.sila.maintenance.ArhamMaterializationCoordinator
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
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
        // دفاعي idempotent: يضمن بدء كل اختبار بنطاق بلا أبناء حتى لو فشل اختبار
        // سابق قبل اكتمال تفكيكه (تجاوز مهلة التصريف الصارم مثلاً).
        IosKoinBootstrap.shutdownForTest()
        stopKoin()
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.resetArhamLifecycleFlagsForTest()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        // إصلاح دورة حياة الموارد المشتركة: تصريف عمل النطاق المحفوظ قبل stopKoin —
        // إغلاق قاعدة SQLite تحت عملٍ حيّ كان يُسقط مضيف الاختبار (SIGSEGV —
        // run 42293935880). الترتيب إلزامي: تصريف → stopKoin → resetMain.
        IosKoinBootstrap.shutdownForTest()
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

        try {
            assertEquals(DefaultTab.CONTACTS, vm.state.value.activeTab)
            vm.onIntent(MainIntent.SelectTab(DefaultTab.ARHAM))
            assertEquals(DefaultTab.ARHAM, vm.state.value.activeTab)
        } finally {
            // نطاق الـ VM خارج engineScope (مالكه الـ ViewModel نفسه ولا يعرفه
            // التصريف) — يُلغى **بالانضمام** هنا قبل تفكيك الحاوية بالنمط المستقر في
            // ContactsViewModelKoinTest، وإلا بقيت مجاميعه (DataStore/DAO) حية عبر
            // stopKoin فتلمس حاوية مغلقة. في finally كي لا يتخطاه فشل تأكيد.
            runBlocking {
                withTimeout(5_000) {
                    runCatching { vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
                }
            }
        }
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
    /** كتابة+قراءة فعليتان على قاعدة الدورة الحالية — تُفشلان لو استُخدمت مراجع قاعدة سابقة مغلقة. */
    private fun writeAndReadOnCurrentContainer(koin: Koin, marker: String) {
        val db = koin.get<LocalSilaDatabase>()
        // runBlocking مقيد بمهلة في كود الاختبار حصراً (سابقة المستودع الإنتاجية:
        // LocalSilaDatabase.kt:196,215-217)، بديل حتمي عن استطلاع usleep على الزمن الحقيقي.
        runBlocking {
            withTimeout(5_000) {
                db.upsertContact(
                    Contact(
                        id = 0L,
                        systemContactId = marker,
                        name = ContactName(displayName = marker),
                        phones = emptyList()
                    )
                )
                val rows = db.observeContacts(category = null, searchQuery = "").first()
                assertTrue(
                    rows.any { it.systemContactId == marker },
                    "the write must be readable back on the same cycle's database"
                )
            }
        }
    }

    @Test
    fun shutdownForTest_repeatedKoinCycles_neverReuseClosedDatabaseReferences() {
        // البرهان الحتمي على الإصلاح: دورتان كاملتان (حاوية → عمل حيّ → تصريف → إغلاق)
        // — قبل الإصلاح كانت الدورة الثانية تضرب قاعدة مغلقة (SQLiteException 21 ثم
        // SIGSEGV). عملية الكتابة/القراءة في كل دورة هي بوابة سلامة المراجع.
        var previousKoin: Koin? = null
        repeat(2) { cycle ->
            IosKoinBootstrap.resetCleanupLaunchForTest()
            val koin = IosKoinBootstrap.ensureStarted()
            if (previousKoin != null) {
                assertNotEquals(previousKoin, koin, "cycle $cycle must run on a fresh container")
            }
            previousKoin = koin
            assertTrue(
                koin.get<LocalSilaDatabase>().dbFilePath.endsWith("/Application Support/Sila/sila_local.db"),
                "cycle $cycle must use the on-disk Application Support database"
            )
            writeAndReadOnCurrentContainer(koin, marker = "cycle_$cycle")

            // التصريف (يلغي وينتظر — يفشل loudly عند تجاوز المهلة) ثم الإغلاق: الترتيب الإلزامي.
            IosKoinBootstrap.shutdownForTest()
            stopKoin()
        }
    }

    @Test
    fun shutdownForTest_isIdempotent_andSafeWithoutLiveContainer() {
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.ensureStarted()

        IosKoinBootstrap.shutdownForTest() // التصريف الأول: إلغاء + انتظار + نطاق طازج
        IosKoinBootstrap.shutdownForTest() // التصريف المتكرر: بلا عمل حيّ — بلا استثناء
        assertFalse(
            IosKoinBootstrap.isCleanupLaunchedForTest(),
            "the drain resets the once-per-process launch guard"
        )
        stopKoin()
        IosKoinBootstrap.shutdownForTest() // بعد stopKoin: سياق عام غائب — أمان كامل
    }

    @Test
    fun shutdownForTest_resetsCleanupLaunchGuard_afterFullDrain() {
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.ensureStarted()
        assertTrue(
            IosKoinBootstrap.isCleanupLaunchedForTest(),
            "ensureStarted must consume the once-per-process guard"
        )

        IosKoinBootstrap.shutdownForTest()

        assertFalse(
            IosKoinBootstrap.isCleanupLaunchedForTest(),
            "the guard may only reset after the lifecycle fully drained — a later test then relaunches fresh"
        )
    }

    @Test
    fun ensureStarted_containerReplacement_drainsEngineScopeBeforeStopKoin() {
        // دورة حيّة أولاً: عمل فعلي في engineScope (محرك + صيانة) ثم تصريف مباشر.
        // لاحظ أن عمل engineScope غير متزامن (خيوط Default حقيقية) فلا يُطالَح
        // المحرك بحالة غير IDLE هنا — لiveness مثبتة باستهلاك علم الصيانة،
        // والانضمام مفروض بحاجز التصريف الصارم (تجاوز المهلة يفشل loudly).
        IosKoinBootstrap.resetCleanupLaunchForTest()
        val liveKoin = IosKoinBootstrap.ensureStarted()
        val liveEngine = liveKoin.get<ContactsSyncEngine>()
        assertTrue(IosKoinBootstrap.isCleanupLaunchedForTest(), "ensureStarted consumes the retention guard")
        IosKoinBootstrap.shutdownForTest()
        assertEquals(
            ContactsSyncState.IDLE,
            liveEngine.state.value,
            "the drain must stop the live engine (state reset after cancelAndJoin completes)"
        )
        assertFalse(
            IosKoinBootstrap.isCleanupLaunchedForTest(),
            "the drain resets the guard only after the lifecycle fully stopped"
        )
        stopKoin() // الإغلاق بعد التصريف — الحاوية ساكنة

        // سيناريو الاستبدال الأجنبي (الوحيد القابل للبناء عبر الواجهات): حاوية
        // :memory: ثم ensureStarted يستبدلها — التصريف عند مسار الاستبدال ينفَّذ،
        // وبرهانه النهائي: إطلاق الصيانة من جديد على الحاوية الطازجة، والحاوية
        // الجديدة تصلح للعمل الفعلي بلا ClosedScopeException ولا SQLiteException.
        val inMemoryKoin = ensureKoinStarted(storageDirPath = null)
        assertEquals(":memory:", inMemoryKoin.get<LocalSilaDatabase>().dbFilePath)

        val diskKoin = IosKoinBootstrap.ensureStarted()
        assertNotEquals(inMemoryKoin, diskKoin, "the mismatched container must be replaced")
        assertTrue(
            diskKoin.get<LocalSilaDatabase>().dbFilePath.endsWith("/Application Support/Sila/sila_local.db"),
            "the replacement container must use on-disk Application Support storage"
        )
        assertTrue(
            IosKoinBootstrap.isCleanupLaunchedForTest(),
            "retention must relaunch on the fresh container after the replacement drain"
        )
        writeAndReadOnCurrentContainer(diskKoin, marker = "replacement_probe")
    }

    @Test
    fun shutdownForTest_resetsArhamLifecycleFlags_afterFullDrain() {
        // أعلام أرحام تُستهلك عند ensureStarted وتُصفَّر بالتصريف فقط — التصريف
        // المدمج صفَّرها (الفجوة الاتحادية للدمج: بلا تصفير تبقى أرحام ميتة لبقية
        // عمر العملية بعد أي استبدال حاوية).
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.ensureStarted()
        assertFalse(
            IosKoinBootstrap.areArhamLifecycleFlagsResetForTest(),
            "ensureStarted must consume the arham lifecycle flags"
        )

        IosKoinBootstrap.shutdownForTest()

        assertTrue(
            IosKoinBootstrap.areArhamLifecycleFlagsResetForTest(),
            "the drain may only reset the arham flags after the lifecycle fully stopped"
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun arhamLifecycle_reArms_onFreshContainer_afterDrainCycle() {
        // البرهان السلوكي (لا تصفير أعلامٍ يدوي قبل الدورة الثانية — التصريف نفسه هو
        // الذي أعاد الأعلام): دورة كاملة (استهلاك → تصريف → إغلاق) ثم حاوية جديدة
        // تُجسِّد «أهم 5» من جديد — قبل الإصلاح كان العلم العالق يمنع أي تجسيد بعد
        // أول تصريف وينتهي الاستطلاع بلا نتيجة.
        IosKoinBootstrap.resetCleanupLaunchForTest()
        IosKoinBootstrap.ensureStarted()
        IosKoinBootstrap.shutdownForTest()
        stopKoin()

        val koin2 = IosKoinBootstrap.ensureStarted()
        val coordinator = koin2.get<ArhamMaterializationCoordinator>()
        var waitedMillis = 0L
        while (coordinator.lastOutcome.value == null && waitedMillis < 15_000L) {
            usleep(100_000u) // 100ms — نمط الاستطلاع المستقر في هذا الملف
            waitedMillis += 100L
        }
        assertNotNull(
            coordinator.lastOutcome.value,
            "top-five materialization must re-arm on the fresh container after a full drain cycle"
        )
        writeAndReadOnCurrentContainer(koin2, marker = "rearm_probe")
    }
}
