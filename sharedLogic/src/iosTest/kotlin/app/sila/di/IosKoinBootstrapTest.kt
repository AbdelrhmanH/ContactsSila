package app.sila.di

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.DefaultTab
import app.sila.feature.main.MainIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import platform.Foundation.NSFileManager
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
}
