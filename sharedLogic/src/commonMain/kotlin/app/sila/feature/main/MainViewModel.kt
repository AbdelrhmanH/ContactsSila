package app.sila.feature.main

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppClock
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.DefaultTab
import app.sila.core.model.Service
import app.sila.core.model.UserPreferences
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.services.domain.ServicesRepository
import app.sila.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

data class MainUiState(
    val activeTab: DefaultTab = DefaultTab.CONTACTS,
    val selectedCategory: Category? = null,
    val contactsSearchQuery: String = "",
    val servicesSearchQuery: String = "",
    val contacts: List<Contact> = emptyList(),
    val favoriteContacts: List<Contact> = emptyList(),
    val categoryCounts: Map<Category, Int> = emptyMap(),
    val totalContactsCount: Int = 0,
    val services: List<Service> = emptyList(),
    val arhamItems: List<ArhamItem> = emptyList(),
    val arhamStats: ArhamDashboardStats = ArhamDashboardStats(),
    val overdueArhamCount: Int = 0,
    val preferences: UserPreferences = UserPreferences(),
    val isSmartAddSheetVisible: Boolean = false,
    val isSettingsSheetVisible: Boolean = false,
    val lastDialedPhone: String? = null
)

sealed interface MainIntent {
    data class SelectTab(val tab: DefaultTab) : MainIntent
    data class SelectCategory(val category: Category?) : MainIntent
    data class UpdateContactsSearch(val query: String) : MainIntent
    data class UpdateServicesSearch(val query: String) : MainIntent
    data class CallNumber(val phone: String, val contactId: Long? = null) : MainIntent
    data object ToggleSmartAddSheet : MainIntent
    data object DismissSmartAddSheet : MainIntent
    data object ToggleSettingsSheet : MainIntent
    data object DismissSettingsSheet : MainIntent
    data object ToggleLanguage : MainIntent
    data class ChangeThemeMode(val mode: AppThemeMode) : MainIntent
    data class ToggleArhamNotifications(val enabled: Boolean) : MainIntent
    data class ToggleSmartFilter(val enabled: Boolean) : MainIntent
}

sealed interface MainEffect {
    data class OpenDialer(val phone: String) : MainEffect
    data class ShowFeedback(val messageAr: String, val messageEn: String) : MainEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val contactsRepository: ContactsRepository,
    private val arhamRepository: ArhamRepository,
    private val servicesRepository: ServicesRepository,
    private val getArhamPriorityList: GetArhamPriorityListUseCase,
    private val getArhamStats: GetArhamDashboardStatsUseCase,
    private val settingsRepository: SettingsRepository,
    private val clock: AppClock
) : BaseMviViewModel<MainUiState, MainIntent, MainEffect>(MainUiState()) {

    private val selectedCategoryFlow = MutableStateFlow<Category?>(null)
    private val contactsSearchFlow = MutableStateFlow("")
    private val servicesSearchFlow = MutableStateFlow("")

    init {
        val filteredContactsFlow = combine(selectedCategoryFlow, contactsSearchFlow) { cat, query ->
            cat to query
        }.flatMapLatest { (cat, query) ->
            contactsRepository.observeContacts(category = cat, searchQuery = query)
        }

        val filteredServicesFlow = servicesSearchFlow.flatMapLatest { query ->
            servicesRepository.observeDirectory(searchQuery = query)
        }

        combine(
            filteredContactsFlow,
            contactsRepository.observeFavorites(),
            contactsRepository.observeCategoryCounts(),
            filteredServicesFlow,
            getArhamPriorityList()
        ) { contacts, favorites, counts, services, arhamList ->
            Quintuple(contacts, favorites, counts, services, arhamList)
        }.combine(
            combine(getArhamStats(), settingsRepository.preferences) { stats, prefs ->
                stats to prefs
            }
        ) { (contacts, favorites, counts, services, arhamList), (stats, prefs) ->
            updateState {
                copy(
                    contacts = contacts,
                    favoriteContacts = favorites,
                    categoryCounts = counts,
                    totalContactsCount = counts.values.sum(),
                    services = services,
                    arhamItems = arhamList,
                    arhamStats = stats,
                    overdueArhamCount = arhamList.count { it.status == ArhamStatus.OVERDUE },
                    preferences = prefs
                )
            }
        }.launchIn(viewModelScope)
    }

    override fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.SelectTab -> updateState { copy(activeTab = intent.tab) }
            is MainIntent.SelectCategory -> {
                selectedCategoryFlow.value = intent.category
                updateState { copy(selectedCategory = intent.category) }
            }
            is MainIntent.UpdateContactsSearch -> {
                contactsSearchFlow.value = intent.query
                updateState { copy(contactsSearchQuery = intent.query) }
            }
            is MainIntent.UpdateServicesSearch -> {
                servicesSearchFlow.value = intent.query
                updateState { copy(servicesSearchQuery = intent.query) }
            }
            is MainIntent.CallNumber -> {
                viewModelScope.launch {
                    if (intent.contactId != null) {
                        arhamRepository.recordContactCall(intent.contactId, clock.nowEpochMillis())
                    }
                    updateState { copy(lastDialedPhone = intent.phone) }
                    sendEffect(MainEffect.OpenDialer(intent.phone))
                }
            }
            MainIntent.ToggleSmartAddSheet -> updateState { copy(isSmartAddSheetVisible = !isSmartAddSheetVisible) }
            MainIntent.DismissSmartAddSheet -> updateState { copy(isSmartAddSheetVisible = false) }
            MainIntent.ToggleSettingsSheet -> updateState { copy(isSettingsSheetVisible = !isSettingsSheetVisible) }
            MainIntent.DismissSettingsSheet -> updateState { copy(isSettingsSheetVisible = false) }
            MainIntent.ToggleLanguage -> {
                viewModelScope.launch {
                    val nextLang = if (currentState.preferences.language == AppLanguage.ARABIC) {
                        AppLanguage.ENGLISH
                    } else {
                        AppLanguage.ARABIC
                    }
                    settingsRepository.setLanguage(nextLang)
                }
            }
            is MainIntent.ChangeThemeMode -> {
                viewModelScope.launch {
                    settingsRepository.setThemeMode(intent.mode)
                }
            }
            is MainIntent.ToggleArhamNotifications -> {
                viewModelScope.launch {
                    settingsRepository.setArhamNotificationsEnabled(intent.enabled)
                }
            }
            is MainIntent.ToggleSmartFilter -> {
                viewModelScope.launch {
                    settingsRepository.setSmartFilterEnabled(intent.enabled)
                }
            }
        }
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
