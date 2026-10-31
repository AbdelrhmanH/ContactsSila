package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.BeforeClass
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * قياسات أداء القائمة والبحث لمعيار T3 (Issue #24: «first frame < 500ms لـ 5000 جهة»).
 *
 * **نطاق القياس الموثّق (لا مبالغة):** هذه قياسات JVM مضيف (Windows، مشغّل SQLite
 * المجمّع) — ليست قياس جهاز. تقيس: (1) زمن كتابة دفعة 5000 جهة، (2) زمن أول إصدار
 * حالة للواجهة كاملة منها، (3) زمن أول إصدار لبحث FTS يطابق 5000 جهة (أسوأ حالة).
 * قياس الجهاز الفعلي يبقى متابعة توثيقية في تقرير PR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsListPerformanceTest {

    private val koin get() = KoinPlatformTools.defaultContext().get()

    companion object {
        private val mainExecutor: java.util.concurrent.ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-main-perf").apply { isDaemon = true }
            }
        private val mainDispatcher: ExecutorCoroutineDispatcher = mainExecutor.asCoroutineDispatcher()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            Dispatchers.setMain(mainDispatcher)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            repeat(times = 3) {
                runCatching { mainExecutor.submit(Runnable {}).get() }
                Thread.sleep(50)
            }
        }

        private const val BATCH_SIZE = 5_000

        private fun fixtureContact(index: Int): Contact = Contact(
            id = 0L,
            systemContactId = "perf_$index",
            name = ContactName(displayName = "جهة الأداء رقم $index"),
            phones = listOf(
                Phone(raw = "0100%06d".format(index), e164 = "+20100%06d".format(index), isPrimary = true)
            ),
            normalizedName = "جهة الأداء رقم $index"
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { koin.getOrNull<ContactsViewModel>()?.viewModelScope?.cancel() }
        stopKoin()
    }

    @Test
    fun fiveThousandContacts_batchWrite_thenFirstFrame_thenSearchLatency() = runTestsWithViewModel { viewModel ->
        val contacts = (0 until BATCH_SIZE).map(::fixtureContact)

        // 1) كتابة دفعة 5000 جهة (مسار T2 حصراً):
        val writeStart = System.nanoTime()
        val dao = koin.get<app.sila.core.database.dao.ContactsSyncDao>()
        dao.applySyncBatch(
            upserts = contacts,
            tombstones = emptyList(),
            snapshotJson = null,
            reconcileStampMillis = null,
            nowMillis = 1_800_000_000_000L
        )
        val writeMillis = (System.nanoTime() - writeStart) / 1_000_000

        // 2) «أول إطار»: أول إصدار حالة غير تحميل بعد التعبئة:
        val frameStart = System.nanoTime()
        val loaded = withTimeoutOrNull(60_000) {
            viewModel.state.first { !it.isLoading && it.totalContactsCount >= BATCH_SIZE }
        } ?: throw IllegalStateException("PERF HUNG: first frame with 5000 contacts")
        val firstFrameMillis = (System.nanoTime() - frameStart) / 1_000_000

        // 3) البحث (أسوأ حالة: بادئة تطابق الـ 5000 كلها):
        val searchStart = System.nanoTime()
        val results = withTimeoutOrNull(60_000) {
            koin.get<app.sila.core.database.dao.ContactDao>()
                .searchContacts("جهة الأداء")
                .first()
        } ?: throw IllegalStateException("PERF HUNG: search over 5000")
        val searchMillis = (System.nanoTime() - searchStart) / 1_000_000

        println(
            "T3-PERF (host JVM): batchWrite(5000)=${writeMillis}ms, " +
                "firstUiState=${firstFrameMillis}ms, " +
                "searchAllMatches=${searchMillis}ms, results=${results.size}"
        )

        assertTrue(loaded.totalContactsCount >= BATCH_SIZE)
        assertEquals(BATCH_SIZE, results.size, "البحث يعيد كل المطابقات (أسوأ حالة)")
        assertTrue(
            firstFrameMillis < 500,
            "معيار أول إطار < 500ms — القياس الفعلي: ${firstFrameMillis}ms"
        )
        assertTrue(searchMillis < 500, "بحث مطابق للجميع ضمن ميزانية أول إطار — فعلياً ${searchMillis}ms")
    }

    /** حامل صغير يفتح نطاق تنفيذ حقيقياً مع الـ Main الحقيقي — Room على القرص يتجمد تحت runTest (درس T5). */
    private fun runTestsWithViewModel(
        block: suspend (ContactsViewModel) -> Unit
    ) = kotlinx.coroutines.runBlocking {
        val storageDir = Files.createTempDirectory("contacts_perf_test_")
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
        val viewModel = koin.get<ContactsViewModel>()
        block(viewModel)
        viewModel.viewModelScope.cancel()
    }
}
