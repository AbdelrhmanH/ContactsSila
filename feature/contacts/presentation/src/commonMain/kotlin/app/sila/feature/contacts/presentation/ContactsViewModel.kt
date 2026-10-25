package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.DeleteContactUseCase
import app.sila.feature.contacts.domain.DetectDuplicatesUseCase
import app.sila.feature.contacts.domain.DuplicateGroup
import app.sila.feature.contacts.domain.ObserveCategoryCountsUseCase
import app.sila.feature.contacts.domain.ObserveContactsUseCase
import app.sila.feature.contacts.domain.ObserveFavoriteContactsUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactsUiState(
    val selectedCategory: Category? = null,
    val searchQuery: String = "",
    val contacts: List<Contact> = emptyList(),
    val favorites: List<Contact> = emptyList(),
    val categoryCounts: Map<Category, Int> = emptyMap(),
    val totalContactsCount: Int = 0,
    val duplicates: List<DuplicateGroup> = emptyList()
)

sealed interface ContactsIntent {
    data class SelectCategory(val category: Category?) : ContactsIntent
    data class UpdateSearchQuery(val query: String) : ContactsIntent
    data class ChangeContactCategory(val contactId: Long, val category: Category) : ContactsIntent
    data class DeleteContact(val contactId: Long) : ContactsIntent
}

sealed interface ContactsEffect {
    data class ShowUndoDelete(val contactId: Long) : ContactsEffect
}

/**
 * ViewModel ميزة جهات الاتصال — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(
    private val observeContacts: ObserveContactsUseCase,
    private val observeFavorites: ObserveFavoriteContactsUseCase,
    private val observeCategoryCounts: ObserveCategoryCountsUseCase,
    private val updateContactCategory: UpdateContactCategoryUseCase,
    private val deleteContact: DeleteContactUseCase,
    private val detectDuplicates: DetectDuplicatesUseCase = DetectDuplicatesUseCase()
) : BaseMviViewModel<ContactsUiState, ContactsIntent, ContactsEffect>(ContactsUiState()) {

    constructor(
        repository: ContactsRepository,
        detectDuplicates: DetectDuplicatesUseCase = DetectDuplicatesUseCase()
    ) : this(
        observeContacts = ObserveContactsUseCase(repository),
        observeFavorites = ObserveFavoriteContactsUseCase(repository),
        observeCategoryCounts = ObserveCategoryCountsUseCase(repository),
        updateContactCategory = UpdateContactCategoryUseCase(repository),
        deleteContact = DeleteContactUseCase(repository),
        detectDuplicates = detectDuplicates
    )

    private val categoryFilter = MutableStateFlow<Category?>(null)
    private val searchFlow = MutableStateFlow("")

    private val contactsFlow = combine(categoryFilter, searchFlow) { cat, q -> cat to q }
        .flatMapLatest { (cat, q) -> observeContacts(cat, q) }

    override val state: StateFlow<ContactsUiState> = combine(
        categoryFilter,
        searchFlow,
        contactsFlow,
        observeFavorites(),
        observeCategoryCounts()
    ) { selectedCategory, searchQuery, list, favs, counts ->
        ContactsUiState(
            selectedCategory = selectedCategory,
            searchQuery = searchQuery,
            contacts = list,
            favorites = favs,
            categoryCounts = counts,
            totalContactsCount = counts.values.sum(),
            duplicates = detectDuplicates(list)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContactsUiState()
    )

    override fun onIntent(intent: ContactsIntent) {
        when (intent) {
            is ContactsIntent.SelectCategory -> {
                categoryFilter.value = intent.category
            }
            is ContactsIntent.UpdateSearchQuery -> {
                searchFlow.value = intent.query
            }
            is ContactsIntent.ChangeContactCategory -> {
                viewModelScope.launch {
                    updateContactCategory(intent.contactId, intent.category)
                }
            }
            is ContactsIntent.DeleteContact -> {
                viewModelScope.launch {
                    deleteContact(intent.contactId)
                    sendEffect(ContactsEffect.ShowUndoDelete(intent.contactId))
                }
            }
        }
    }
}
