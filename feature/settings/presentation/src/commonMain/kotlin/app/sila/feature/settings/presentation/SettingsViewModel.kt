package app.sila.feature.settings.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.ObserveUserPreferencesUseCase
import app.sila.feature.settings.domain.SetArhamNotificationsUseCase
import app.sila.feature.settings.domain.SetLanguageUseCase
import app.sila.feature.settings.domain.SetSmartFilterUseCase
import app.sila.feature.settings.domain.SetThemeModeUseCase
import app.sila.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences()
)

sealed interface SettingsIntent {
    data class SetThemeMode(val mode: AppThemeMode) : SettingsIntent
    data class SetLanguage(val language: AppLanguage) : SettingsIntent
    data class SetArhamNotifications(val enabled: Boolean) : SettingsIntent
    data class SetSmartFilter(val enabled: Boolean) : SettingsIntent
}

sealed interface SettingsEffect

/**
 * ViewModel ميزة الإعدادات — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 */
class SettingsViewModel(
    private val observePreferences: ObserveUserPreferencesUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setLanguage: SetLanguageUseCase,
    private val setArhamNotifications: SetArhamNotificationsUseCase,
    private val setSmartFilter: SetSmartFilterUseCase
) : BaseMviViewModel<SettingsUiState, SettingsIntent, SettingsEffect>(SettingsUiState()) {

    constructor(repository: SettingsRepository) : this(
        observePreferences = ObserveUserPreferencesUseCase(repository),
        setThemeMode = SetThemeModeUseCase(repository),
        setLanguage = SetLanguageUseCase(repository),
        setArhamNotifications = SetArhamNotificationsUseCase(repository),
        setSmartFilter = SetSmartFilterUseCase(repository)
    )

    override val state: StateFlow<SettingsUiState> = observePreferences()
        .map { prefs -> SettingsUiState(preferences = prefs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState()
        )

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetThemeMode -> viewModelScope.launch { setThemeMode(intent.mode) }
            is SettingsIntent.SetLanguage -> viewModelScope.launch { setLanguage(intent.language) }
            is SettingsIntent.SetArhamNotifications -> viewModelScope.launch { setArhamNotifications(intent.enabled) }
            is SettingsIntent.SetSmartFilter -> viewModelScope.launch { setSmartFilter(intent.enabled) }
        }
    }
}
