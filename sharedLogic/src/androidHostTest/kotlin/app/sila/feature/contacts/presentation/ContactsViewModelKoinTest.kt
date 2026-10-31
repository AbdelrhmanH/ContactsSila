package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.database.dao.ContactDao
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.di.createSilaSharedModule
import app.sila.feature.contacts.domain.SuggestionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.BeforeClass
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات حالة العرض لـ [ContactsViewModel] فوق حاوية Koin الحقيقية
 * (`createSilaSharedModule`) — Issue #24 (T3). نفس منهجية T5 المجرَّبة:
 * Main بزمن حقيقي أحادي الخيط، Room/DataStore في الذاكرة، وانتظار عبر
 * `state.first { }` — الـ debounce (150ms) زمني حقيقي فيُستخدم بالانتظار المعلّق.
 *
 * ملاحظة بذور العرض: القاعدة الفارغة تُبذَّر بجهات عرض — التأكيدات نسبية لعدّادات
 * هذا الاختبار (بادئة `t3_` في `systemContactId`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelKoinTest {

    private val koin get() = KoinPlatformTools.defaultContext().get()

    companion object {
        /** Main حقيقي أحادي الخيط (مرآة لخيط Main على أندرويد) — واحد لعالم الصنف كاملاً. */
        private val mainExecutor: java.util.concurrent.ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-main-contacts").apply { isDaemon = true }
            }
        private val mainDispatcher: ExecutorCoroutineDispatcher = mainExecutor.asCoroutineDispatcher()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // نفس مبرر T5: UnconfinedTestDispatcher يجمّد سلسلة stateIn فوق تدفق Room،
            // والمُجدوِل التنفيذي الحقيقي يشغّل إصدارات Room/DataStore بزمن حقيقي.
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
    }

    @AfterTest
    fun tearDown() {
        runCatching { koin.getOrNull<ContactsViewModel>()?.viewModelScope?.cancel() }
        stopKoin()
    }

    // ===== أدوات =====

    private fun contactsViewModel(): ContactsViewModel = koin.get()

    private fun contactDao(): ContactDao = koin.get()

    private fun preferences(): UserPreferencesDataSource = koin.get()

    private suspend fun <T> await(label: String, block: suspend () -> T): T =
        withTimeoutOrNull(60_000) { block() } ?: throw IllegalStateException("AWAIT HUNG: $label")

    private suspend fun t3Contacts(state: ContactsUiState): List<Contact> =
        state.contacts.filter { it.systemContactId.startsWith("t3_") }

    private suspend fun upsertFixture(
        systemContactId: String,
        displayName: String,
        category: Category = Category.UNCATEGORIZED,
        phones: List<Phone> = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
    ): Long {
        contactDao().upsertContact(
            Contact(
                id = 0L,
                systemContactId = systemContactId,
                name = ContactName(displayName = displayName),
                phones = phones,
                category = category,
                normalizedName = displayName
            )
        )
        return await("upsert visible $systemContactId") {
            contactsViewModel().state.first { state ->
                state.contacts.any { it.systemContactId == systemContactId }
            }.contacts.first { it.systemContactId == systemContactId }.id
        }
    }

    // ===== الاختبارات =====

    @Test
    fun listLoads_fromRoomThroughFullPipeline() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()

        val state = await("initial load") { viewModel.state.first { !it.isLoading } }
        assertTrue(state.contacts.isNotEmpty(), "بذور العرض تُقرأ من Room عبر الخط كاملاً")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun search_isDebounced_andReturnsFtsResults_thenBlankRestoresAll() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        upsertFixture("t3_search", "أحمد الخالد المميز")

        // نتائج FTS أثناء الكتابة (debounce 150ms زمني حقيقي — الانتظار المعلّق يقطعه):
        viewModel.onIntent(ContactsIntent.UpdateSearchQuery("الخالد المميز"))
        val searchState = await("search results") { viewModel.state.first { it.isSearchActive && t3Contacts(it).isNotEmpty() } }
        assertEquals(1, t3Contacts(searchState).size, "نتيجة FTS واحدة لعدّاد الاختبار")

        // عودة الاستعلام الفارغ إلى كل الجهات الحيّة:
        viewModel.onIntent(ContactsIntent.UpdateSearchQuery(""))
        await("blank restores") { viewModel.state.first { !it.isSearchActive && it.contacts.isNotEmpty() } }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun search_noResults_isAnEmptySearchState_notError() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        viewModel.onIntent(ContactsIntent.UpdateSearchQuery("zzzz_لا_مطابقة_إطلاقاً"))
        val state = await("no-results state") {
            viewModel.state.first { it.isSearchActive && it.contacts.isEmpty() }
        }
        assertTrue(!state.hasError, "لا نتائج بحث حالة عادية ليست خطأ")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun chipSelection_survivesBackNavigation_becauseViewModelIsKoinSingle() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()

        viewModel.onIntent(ContactsIntent.SelectCategory(Category.FAMILY))
        await("chip selected") { viewModel.state.first { it.selectedCategory == Category.FAMILY } }

        // "يعود" المستخدم: نفس الـ single من Koin — الحالة محفوظة (عقد معيار القبول).
        val reResolved = contactsViewModel()
        assertEquals(viewModel, reResolved, "الـ ViewModel Koin single واحد")
        assertEquals(Category.FAMILY, reResolved.state.value.selectedCategory)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun bulkCategorize_updatesListAndCounts() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        val id1 = upsertFixture("t3_bulk_a", "جهة جماعية أ")
        val id2 = upsertFixture("t3_bulk_b", "جهة جماعية ب")
        val countsBefore = await("counts before") {
            viewModel.state.first { it.categoryCounts.isNotEmpty() }.categoryCounts[Category.FAMILY] ?: 0
        }

        viewModel.onIntent(ContactsIntent.ToggleSelect(id1))
        viewModel.onIntent(ContactsIntent.ToggleSelect(id2))
        await("selection 2") { viewModel.state.first { it.selection.size == 2 } }

        viewModel.onIntent(ContactsIntent.BulkCategorize(Category.FAMILY))

        val after = await("bulk categorized") {
            viewModel.state.first { state ->
                state.selection.isEmpty() &&
                    t3Contacts(state).all { it.category == Category.FAMILY }
            }
        }
        assertEquals(
            countsBefore + 2,
            after.categoryCounts[Category.FAMILY],
            "العدادات الحية تتضمن التصنيف الجماعي فوراً"
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun toggleFavorite_persistsAcrossViewModelState() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        val id = upsertFixture("t3_fav", "جهة المفضلة")

        viewModel.onIntent(ContactsIntent.ToggleFavorite(id, true))
        await("favorite on") { viewModel.state.first { state -> state.favorites.any { it.id == id } } }

        viewModel.onIntent(ContactsIntent.ToggleFavorite(id, false))
        await("favorite off") { viewModel.state.first { state -> state.favorites.none { it.id == id } } }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun selection_clearsAfterBulkDelete_andRowsLeaveList() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        val id = upsertFixture("t3_delete", "جهة الحذف")

        viewModel.onIntent(ContactsIntent.ToggleSelect(id))
        await("selected") { viewModel.state.first { it.selection == setOf(id) } }

        // الحذف يُرسل بعد تأكيد المستخدم في العرض (التأكيد نفسه UI — هنا العقد):
        viewModel.onIntent(ContactsIntent.BulkDelete)

        val after = await("deleted") {
            viewModel.state.first { state ->
                state.selection.isEmpty() && state.contacts.none { it.id == id }
            }
        }
        assertTrue(after.contacts.none { it.systemContactId == "t3_delete" })
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun callIntent_singlePhone_emitsDialerEffect_directly() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        val id = upsertFixture(
            "t3_call",
            "جهة الاتصال",
            phones = listOf(Phone(raw = "01055555555", e164 = "+201055555555", isPrimary = true))
        )

        val effects = mutableListOf<ContactsEffect>()
        val collector = launch { viewModel.effects.take(1).toList(effects) }
        viewModel.onIntent(ContactsIntent.Call(id))
        collector.join()

        assertEquals(ContactsEffect.OpenDialer("+201055555555", id), effects.single(), "الاتصال يفتح الهاتف عبر الأثر")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun callIntent_multiPhone_emitsNumberPickerEffect_insteadOfGuessing() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        val id = upsertFixture(
            "t3_multiphone",
            "جهة بعدة أرقام",
            phones = listOf(
                Phone(raw = "01111111111", e164 = "+201111111111", isPrimary = false),
                Phone(raw = "01222222222", e164 = "+201222222222", isPrimary = false)
            )
        )

        val effects = mutableListOf<ContactsEffect>()
        val collector = launch { viewModel.effects.take(1).toList(effects) }
        viewModel.onIntent(ContactsIntent.Call(id))
        collector.join()

        val picker = effects.single() as ContactsEffect.ShowNumberPicker
        assertEquals(2, picker.phones.size, "تعددت الأرقام بلا أساسي ⇒ منتقي أرقام (معيار القبول)")
        assertEquals(id, picker.contactId)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun suggestionDismissal_persistsToDataStore_andCardDoesNotReturn() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = contactsViewModel()
        await("initial load") { viewModel.state.first { !it.isLoading } }

        // مجموعة مكررات مضمونة عبر عدّادات الاختبار:
        upsertFixture("t3_dup_a", "زينب المكررة الخالصة")
        upsertFixture("t3_dup_b", "زينب المكررة الخالصة")

        val card = await("duplicates card") {
            viewModel.state.first { state ->
                state.suggestions.any { it.kind == SuggestionCard.Kind.DUPLICATES }
            }.suggestions.first { it.kind == SuggestionCard.Kind.DUPLICATES }
        }

        viewModel.onIntent(ContactsIntent.DismissSuggestion(card.id))

        // الكتابة تصل DataStore (SSOT) ثم لا تعود البطاقة لنفس العناصر:
        await("dismissal persisted") {
            preferences().preferences.first { card.id in it.dismissedSuggestionIds }
        }
        val after = await("card hidden") {
            viewModel.state.first { state -> state.suggestions.none { it.id == card.id } }
        }
        assertTrue(after.suggestions.none { it.id == card.id })
        viewModel.viewModelScope.cancel()
    }
}
