package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.AccountFilter
import app.sila.feature.contacts.domain.BulkCategorizeUseCase
import app.sila.feature.contacts.domain.BulkSetFavoriteUseCase
import app.sila.feature.contacts.domain.ContactsListPreferencesRepository
import app.sila.feature.contacts.domain.ContactsSyncState
import app.sila.feature.contacts.domain.DeleteContactUseCase
import app.sila.feature.contacts.domain.DeleteContactsUseCase
import app.sila.feature.contacts.domain.ObserveCategoryCountsUseCase
import app.sila.feature.contacts.domain.ObserveContactsPagedUseCase
import app.sila.feature.contacts.domain.ObserveFavoriteContactsUseCase
import app.sila.feature.contacts.domain.ObserveSuggestionsUseCase
import app.sila.feature.contacts.domain.RecordInAppCallUseCase
import app.sila.feature.contacts.domain.SuggestionCard
import app.sila.feature.contacts.domain.SyncStateSource
import app.sila.feature.contacts.domain.ToggleFavoriteUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * حالة شاشة جهات الاتصال (Issue #24 · docs/features/02-contacts-list.md).
 *
 * **عقد مشترك (Shared contract):** تستهلكه T4 (ربط iOS) و T6 (التفاصيل/الإضافة) —
 * أي تغيير في حقوله تغيير عقد مشترك يتطلب مراجعة منسّقة.
 */
data class ContactsUiState(
    val isLoading: Boolean = true,
    val selectedCategory: Category? = null,
    val searchQuery: String = "",
    /** القائمة المرئية (بعد تكوين: حساب × تصنيف × بحث) — مفاتيح ثابتة `contact.id`. */
    val contacts: List<Contact> = emptyList(),
    val favorites: List<Contact> = emptyList(),
    val categoryCounts: Map<Category, Int> = emptyMap(),
    val totalContactsCount: Int = 0,
    val accountFilter: AccountFilter = AccountFilter.ALL,
    val suggestions: List<SuggestionCard> = emptyList(),
    /** معرّفات الجهات المحددة (وضع الاختيار المتعدد) — مفتاح الثبات `contact.id`. */
    val selection: Set<Long> = emptySet(),
    /** أفعال السحب من تفضيلات المستخدم (افتراضي: اتصال/رسالة — الإعداد T9). */
    val swipeStartAction: ContactSwipeAction = ContactSwipeAction.CALL,
    val swipeEndAction: ContactSwipeAction = ContactSwipeAction.MESSAGE,
    val syncState: ContactsSyncState = ContactsSyncState.IDLE
) {
    val isSearchActive: Boolean get() = searchQuery.isNotBlank()
    val isSelectionMode: Boolean get() = selection.isNotEmpty()
    val isPermissionDenied: Boolean get() = syncState == ContactsSyncState.PERMISSION_DENIED
    val hasError: Boolean get() = syncState == ContactsSyncState.FAILED
}

/** نوايا شاشة جهات الاتصال (Issue #24) — العرض يُرسل ولا يحمل منطق أعمال. */
sealed interface ContactsIntent {
    data class SelectCategory(val category: Category?) : ContactsIntent
    data class UpdateSearchQuery(val query: String) : ContactsIntent
    data class SetAccountFilter(val filter: AccountFilter) : ContactsIntent
    data class ChangeContactCategory(val contactId: Long, val category: Category) : ContactsIntent
    data class ToggleFavorite(val contactId: Long, val isFavorite: Boolean) : ContactsIntent
    data class DeleteContact(val contactId: Long) : ContactsIntent

    /** ضغطة مطولة تفتح الاختيار المتعدد وتحدد الصف المضغوط (Issue #24). */
    data class ToggleSelect(val contactId: Long) : ContactsIntent
    data object ClearSelection : ContactsIntent
    data class BulkCategorize(val category: Category) : ContactsIntent
    data class BulkSetFavorite(val isFavorite: Boolean) : ContactsIntent

    /** يُرسل بعد تأكيد الحذف في العرض (Issue #24 — تأكيد حصراً). */
    data object BulkDelete : ContactsIntent
    data object ShareSelection : ContactsIntent
    data class DismissSuggestion(val suggestionId: String) : ContactsIntent

    /** اتصال بالرقم الافتراضي، أو فتح منتقي الأرقام إذا تعددت (Issue #24). */
    data class Call(val contactId: Long) : ContactsIntent
    data class CallNumber(val contactId: Long, val e164: String) : ContactsIntent
    data class Message(val contactId: Long, val e164: String) : ContactsIntent
    data class Swipe(val contactId: Long, val action: ContactSwipeAction) : ContactsIntent

    /** إعادة تحميل القائمة — ملاحظة: المزامنة تتعافى ذاتياً (إعادة محاولة صريحة للمحرك: متابعة T2). */
    data object Retry : ContactsIntent

    /** فتح إعدادات التطبيق (منح الصلاحية نهائياً — المسار الوحيد بعد "لا تسأل مجدداً"). */
    data object OpenSettings : ContactsIntent
}

/** آثار جانبية منصات للغلاف (تُجمَع في `androidApp`/T4) — بلا منطق أعمال. */
sealed interface ContactsEffect {
    data class OpenDialer(val e164: String, val contactId: Long) : ContactsEffect
    data class OpenSms(val e164: String, val contactId: Long) : ContactsEffect
    data class ShareContacts(val text: String) : ContactsEffect
    data object OpenAppSettings : ContactsEffect

    /** تعددت الأرقام — العرض يعرض المنتقي ثم يُرسل CallNumber/Message بالرقم المختار. */
    data class ShowNumberPicker(
        val contactId: Long,
        val phones: List<Phone>,
        /** الفعل المطلوب بعد الاختيار (اتصال أو رسالة) — الافتراضي اتصال. */
        val action: ContactSwipeAction = ContactSwipeAction.CALL
    ) : ContactsEffect
}

/**
 * ViewModel ميزة جهات الاتصال — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 *
 * مسار البحث: استعلام خام → `debounce(150ms)` هنا (معيار القبول — Issue #24) →
 * النطاق يركّب النتائج (FTS عبر `SearchContactsUseCase` × التصنيف × الحساب) داخل
 * [ObserveContactsPagedUseCase] — العرض لا يعرف تفاصيل FTS/SQLite إطلاقاً.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ContactsViewModel(
    private val observeContactsPaged: ObserveContactsPagedUseCase,
    private val observeFavorites: ObserveFavoriteContactsUseCase,
    private val observeCategoryCounts: ObserveCategoryCountsUseCase,
    private val changeContactCategory: UpdateContactCategoryUseCase,
    private val bulkCategorize: BulkCategorizeUseCase,
    private val deleteContact: DeleteContactUseCase,
    private val deleteContacts: DeleteContactsUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val bulkSetFavorite: BulkSetFavoriteUseCase,
    private val recordInAppCall: RecordInAppCallUseCase,
    private val observeSuggestions: ObserveSuggestionsUseCase,
    private val listPreferences: ContactsListPreferencesRepository,
    private val syncStateSource: SyncStateSource,
) : BaseMviViewModel<ContactsUiState, ContactsIntent, ContactsEffect>(ContactsUiState()) {

    private val categoryFilter = MutableStateFlow<Category?>(null)
    private val accountFilterFlow = MutableStateFlow(AccountFilter.ALL)
    private val searchFlow = MutableStateFlow("")
    private val selectionFlow = MutableStateFlow<Set<Long>>(emptySet())
    private val retryTick = MutableStateFlow(0)

    private val debouncedQuery = searchFlow.debounce(timeoutMillis = SEARCH_DEBOUNCE_MILLIS)

    private data class Filters(val category: Category?, val account: AccountFilter, val query: String, val tick: Int)

    private val listFlow = combine(
        categoryFilter,
        accountFilterFlow,
        debouncedQuery,
        retryTick
    ) { category, account, query, tick ->
        Filters(category, account, query, tick)
    }.flatMapLatest { filters ->
        observeContactsPaged(
            category = filters.category,
            searchQuery = filters.query,
            accountFilter = filters.account
        )
    }

    private val countsFlow = accountFilterFlow
        .flatMapLatest { account -> observeCategoryCounts(account) }

    override val state: StateFlow<ContactsUiState> = combine(
        combine(
            listFlow,
            observeFavorites(),
            countsFlow,
            observeSuggestions()
        ) { list, favorites, counts, suggestions ->
            CombinedStreams(list, favorites, counts, suggestions)
        },
        combine(categoryFilter, accountFilterFlow, searchFlow, selectionFlow) { cat, acc, q, sel ->
            FilterEcho(cat, acc, q, sel)
        },
        syncStateSource.observeSyncState(),
        listPreferences.swipeActions
    ) { streams, echo, sync, swipe ->
        ContactsUiState(
            isLoading = false,
            selectedCategory = echo.category,
            searchQuery = echo.query,
            contacts = streams.list,
            favorites = streams.favorites,
            categoryCounts = streams.counts,
            totalContactsCount = streams.counts.values.sum(),
            accountFilter = echo.account,
            suggestions = streams.suggestions,
            selection = echo.selection,
            swipeStartAction = swipe.start,
            swipeEndAction = swipe.end,
            syncState = sync
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContactsUiState()
    )

    private data class CombinedStreams(
        val list: List<Contact>,
        val favorites: List<Contact>,
        val counts: Map<Category, Int>,
        val suggestions: List<SuggestionCard>
    )

    private data class FilterEcho(
        val category: Category?,
        val account: AccountFilter,
        val query: String,
        val selection: Set<Long>
    )

    override fun onIntent(intent: ContactsIntent) {
        when (intent) {
            is ContactsIntent.SelectCategory -> categoryFilter.value = intent.category
            is ContactsIntent.UpdateSearchQuery -> searchFlow.value = intent.query
            is ContactsIntent.SetAccountFilter -> accountFilterFlow.value = intent.filter
            is ContactsIntent.ChangeContactCategory -> viewModelScope.launch {
                changeContactCategory(intent.contactId, intent.category)
            }
            is ContactsIntent.ToggleFavorite -> viewModelScope.launch {
                toggleFavorite(intent.contactId, intent.isFavorite)
            }
            is ContactsIntent.DeleteContact -> viewModelScope.launch {
                deleteContact(intent.contactId)
            }
            is ContactsIntent.ToggleSelect -> selectionFlow.updateSelection(intent.contactId)
            is ContactsIntent.ClearSelection -> selectionFlow.value = emptySet()
            is ContactsIntent.BulkCategorize -> {
                val targets = selectionFlow.value
                viewModelScope.launch {
                    bulkCategorize(targets, intent.category)
                    selectionFlow.value = emptySet()
                }
            }
            is ContactsIntent.BulkSetFavorite -> {
                val targets = selectionFlow.value
                viewModelScope.launch {
                    bulkSetFavorite(targets, intent.isFavorite)
                    selectionFlow.value = emptySet()
                }
            }
            is ContactsIntent.BulkDelete -> {
                val targets = selectionFlow.value
                viewModelScope.launch {
                    deleteContacts(targets)
                    selectionFlow.value = emptySet()
                }
            }
            is ContactsIntent.ShareSelection -> {
                val targets = selectionFlow.value
                viewModelScope.launch {
                    sendEffect(ContactsEffect.ShareContacts(shareText(targets)))
                    selectionFlow.value = emptySet()
                }
            }
            is ContactsIntent.DismissSuggestion -> viewModelScope.launch {
                listPreferences.dismissSuggestion(intent.suggestionId)
            }
            is ContactsIntent.Call -> resolveCall(intent.contactId, smsAction = null)
            is ContactsIntent.CallNumber -> viewModelScope.launch {
                recordInAppCall(intent.e164)
                sendEffect(ContactsEffect.OpenDialer(intent.e164, intent.contactId))
            }
            is ContactsIntent.Message -> viewModelScope.launch {
                sendEffect(ContactsEffect.OpenSms(intent.e164, intent.contactId))
            }
            is ContactsIntent.Swipe -> resolveCall(intent.contactId, smsAction = intent.action)
            is ContactsIntent.Retry -> retryTick.value = retryTick.value + 1
            is ContactsIntent.OpenSettings -> sendEffect(ContactsEffect.OpenAppSettings)
        }
    }

    /** الرقم الافتراضي: الأساسي ثم الوحيد؛ إن تعددت ⇒ منتقي أرقام (معيار قبول Issue #24). */
    private fun resolveCall(contactId: Long, smsAction: ContactSwipeAction?) {
        val action = smsAction ?: ContactSwipeAction.CALL
        val latest = latestState()
        val contact = latest.contacts.find { it.id == contactId }
            ?: latest.favorites.find { it.id == contactId }
        val phones = contact?.phones.orEmpty()
        when {
            phones.isEmpty() -> Unit // بلا رقم — لا فعل (لا اختراع سلوك).
            phones.size == 1 -> dispatchResolved(contactId, phones.first().e164, action)
            else -> {
                val primary = phones.firstOrNull { it.isPrimary }
                if (primary != null) {
                    dispatchResolved(contactId, primary.e164, action)
                } else {
                    sendEffect(ContactsEffect.ShowNumberPicker(contactId, phones, action))
                }
            }
        }
    }

    /**
     * أحدث حالة معروفة لقرارات النوايا المتزامنة (الاتصال/المشاركة): شاشة القائمة مشتركة
     * في [state] طوال ظهورها فالقيمة هي الأحدث عملياً، و[stateIn] يحفظ آخر إصدار حتى
     * عبر فجوات إعادة الاشتراك القصيرة. ملاحظة: `currentState` الأساسي لا يُستخدم —
     * الحالة تُركَّب عبر `combine().stateIn` (نمط T5) ولا تمر عبر `updateState`.
     */
    private fun latestState(): ContactsUiState = state.value

    private fun dispatchResolved(contactId: Long, e164: String, smsAction: ContactSwipeAction?) {
        when (smsAction) {
            ContactSwipeAction.MESSAGE -> onIntent(ContactsIntent.Message(contactId, e164))
            else -> onIntent(ContactsIntent.CallNumber(contactId, e164))
        }
    }

    private fun shareText(selectedIds: Set<Long>): String {
        val selected = latestState().contacts.filter { it.id in selectedIds }
        return selected.joinToString(separator = "\n") { contact ->
            val phone = (contact.phones.firstOrNull { it.isPrimary } ?: contact.phones.firstOrNull())?.e164
            buildString {
                append(contact.name.displayName)
                phone?.let { append(" ").append(it) }
            }
        }
    }

    private fun MutableStateFlow<Set<Long>>.updateSelection(contactId: Long) {
        value = if (contactId in value) value - contactId else value + contactId
    }

    companion object {
        /** معيار القبول: النتائج أثناء الكتابة مع Debounce 150ms (Issue #24). */
        const val SEARCH_DEBOUNCE_MILLIS = 150L
    }
}
