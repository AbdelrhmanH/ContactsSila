package app.sila.feature.settings.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.ExportBackupUseCase
import app.sila.feature.settings.domain.ObserveUserPreferencesUseCase
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.PreviewBackupUseCase
import app.sila.feature.settings.domain.RestoreBackupUseCase
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.RingtoneItem
import app.sila.feature.settings.domain.SetArhamNotificationsUseCase
import app.sila.feature.settings.domain.SetCategoryRingtoneUseCase
import app.sila.feature.settings.domain.SetLanguageUseCase
import app.sila.feature.settings.domain.SetSmartFilterUseCase
import app.sila.feature.settings.domain.SetThemeModeUseCase
import app.sila.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val categoryRingtones: Map<Category, String> = emptyMap(),
    val availableRingtones: List<RingtoneItem> = emptyList(),
    // حالة النسخ الاحتياطي والاستعادة
    val isExportingBackup: Boolean = false,
    val isImportingBackup: Boolean = false,
    val backupExportSuccess: Boolean = false,
    val lastExportedFilePath: String? = null,
    val backupPreview: BackupPreview? = null,
    val restoreResult: RestoreResult? = null,
    val backupErrorMessage: String? = null,
    // حالات الحوارات
    val showExportPasswordDialog: Boolean = false,
    val showImportPasswordDialog: Boolean = false,
    val showRestorePreviewDialog: Boolean = false,
    val showRingtonePickerCategory: Category? = null,
    val showRingtoneGuidanceDialog: Boolean = false
)

sealed interface SettingsIntent {
    data class SetThemeMode(val mode: AppThemeMode) : SettingsIntent
    data class SetLanguage(val language: AppLanguage) : SettingsIntent
    data class SetDigits(val digits: AppDigits) : SettingsIntent
    data class SetNameOrder(val order: NameOrder) : SettingsIntent
    data class SetDefaultTab(val tab: DefaultTab) : SettingsIntent
    data class SetVisibleAccounts(val accounts: Set<String>) : SettingsIntent
    data class SetDefaultSimSlot(val slot: Int?) : SettingsIntent
    data class SetArhamNotifications(val enabled: Boolean) : SettingsIntent
    data class SetArhamNotificationTime(val hour: Int, val minute: Int) : SettingsIntent
    data class SetArhamMaxPerDay(val max: Int) : SettingsIntent
    data class SetQuietHours(val startHour: Int, val endHour: Int) : SettingsIntent
    data class SetDashboardPeriod(val period: DashboardPeriod) : SettingsIntent
    data class SetConfettiEnabled(val enabled: Boolean) : SettingsIntent
    data class SetAutoTrackingEnabled(val enabled: Boolean) : SettingsIntent
    data class SetDefaultCity(val city: String?) : SettingsIntent
    data class SetSearchRadiusKm(val radiusKm: Int) : SettingsIntent
    data class SetQuickRatingEnabled(val enabled: Boolean) : SettingsIntent
    data class SetAutoUpdatePhonebookServices(val enabled: Boolean) : SettingsIntent
    data class SetSmartFilter(val enabled: Boolean) : SettingsIntent
    data class SetDuplicateScanEnabled(val enabled: Boolean) : SettingsIntent
    data class SetPostCallCategorizeEnabled(val enabled: Boolean) : SettingsIntent
    data class SetCrashReportsEnabled(val enabled: Boolean) : SettingsIntent
    data class SetInterestTags(val tags: Set<String>) : SettingsIntent
    data class SetSwipeActions(val start: ContactSwipeAction, val end: ContactSwipeAction) : SettingsIntent
    data class SetCategoryRingtone(val category: Category, val uri: String?) : SettingsIntent

    // حوارات وإجراءات النسخ الاحتياطي
    data object ShowExportDialog : SettingsIntent
    data object DismissExportDialog : SettingsIntent
    data class ExportBackup(val password: String) : SettingsIntent
    data object ShowImportDialog : SettingsIntent
    data object DismissImportDialog : SettingsIntent
    data class PreviewBackupFile(val cipherBytes: ByteArray, val password: String) : SettingsIntent
    data class ConfirmRestore(
        val cipherBytes: ByteArray,
        val password: String,
        val mode: RestoreMode,
        val restorePreferences: Boolean
    ) : SettingsIntent
    data object DismissRestorePreviewDialog : SettingsIntent
    data object DismissError : SettingsIntent

    // حوارات النغمات
    data class OpenRingtonePicker(val category: Category) : SettingsIntent
    data object DismissRingtonePicker : SettingsIntent
    data object ShowRingtoneGuidance : SettingsIntent
    data object DismissRingtoneGuidance : SettingsIntent
}

sealed interface SettingsEffect {
    data class BackupExported(val bytes: ByteArray) : SettingsEffect
    data class BackupRestored(val result: RestoreResult) : SettingsEffect
    data class ShowToast(val message: String) : SettingsEffect
    data object OpenIosContactsApp : SettingsEffect
}

/**
 * ViewModel ميزة الإعدادات والتفضيلات — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 */
class SettingsViewModel(
    private val observePreferences: ObserveUserPreferencesUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setLanguage: SetLanguageUseCase,
    private val setArhamNotifications: SetArhamNotificationsUseCase,
    private val setSmartFilter: SetSmartFilterUseCase,
    private val repository: SettingsRepository? = null,
    private val exportBackup: ExportBackupUseCase? = null,
    private val previewBackup: PreviewBackupUseCase? = null,
    private val restoreBackup: RestoreBackupUseCase? = null,
    private val setCategoryRingtoneUseCase: SetCategoryRingtoneUseCase? = null
) : BaseMviViewModel<SettingsUiState, SettingsIntent, SettingsEffect>(SettingsUiState()) {

    constructor(
        repository: SettingsRepository,
        cryptoEngine: BackupCryptoEngine? = null,
        platformRingtoneSource: PlatformRingtoneSource? = null
    ) : this(
        observePreferences = ObserveUserPreferencesUseCase(repository),
        setThemeMode = SetThemeModeUseCase(repository),
        setLanguage = SetLanguageUseCase(repository),
        setArhamNotifications = SetArhamNotificationsUseCase(repository),
        setSmartFilter = SetSmartFilterUseCase(repository),
        repository = repository,
        exportBackup = cryptoEngine?.let { ExportBackupUseCase(repository, it) },
        previewBackup = cryptoEngine?.let { PreviewBackupUseCase(it) },
        restoreBackup = cryptoEngine?.let { RestoreBackupUseCase(repository, it) },
        setCategoryRingtoneUseCase = platformRingtoneSource?.let { SetCategoryRingtoneUseCase(repository, it) }
    )

    private val _uiState = MutableStateFlow(SettingsUiState())

    override val state: StateFlow<SettingsUiState> = combine(
        observePreferences(),
        _uiState
    ) { prefs, ui ->
        ui.copy(
            preferences = prefs,
            categoryRingtones = prefs.categoryRingtones
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetThemeMode -> viewModelScope.launch { setThemeMode(intent.mode) }
            is SettingsIntent.SetLanguage -> viewModelScope.launch { setLanguage(intent.language) }
            is SettingsIntent.SetDigits -> viewModelScope.launch { repository?.setDigits(intent.digits) }
            is SettingsIntent.SetNameOrder -> viewModelScope.launch { repository?.setNameOrder(intent.order) }
            is SettingsIntent.SetDefaultTab -> viewModelScope.launch { repository?.setDefaultTab(intent.tab) }
            is SettingsIntent.SetVisibleAccounts -> viewModelScope.launch { repository?.setVisibleAccounts(intent.accounts) }
            is SettingsIntent.SetDefaultSimSlot -> viewModelScope.launch { repository?.setDefaultSimSlot(intent.slot) }
            is SettingsIntent.SetArhamNotifications -> viewModelScope.launch { setArhamNotifications(intent.enabled) }
            is SettingsIntent.SetArhamNotificationTime -> viewModelScope.launch {
                repository?.setArhamNotificationTime(intent.hour, intent.minute)
            }
            is SettingsIntent.SetArhamMaxPerDay -> viewModelScope.launch { repository?.setArhamMaxPerDay(intent.max) }
            is SettingsIntent.SetQuietHours -> viewModelScope.launch {
                repository?.setQuietHours(intent.startHour, intent.endHour)
            }
            is SettingsIntent.SetDashboardPeriod -> viewModelScope.launch { repository?.setDashboardPeriod(intent.period) }
            is SettingsIntent.SetConfettiEnabled -> viewModelScope.launch { repository?.setConfettiEnabled(intent.enabled) }
            is SettingsIntent.SetAutoTrackingEnabled -> viewModelScope.launch { repository?.setAutoTrackingEnabled(intent.enabled) }
            is SettingsIntent.SetDefaultCity -> viewModelScope.launch { repository?.setDefaultCity(intent.city) }
            is SettingsIntent.SetSearchRadiusKm -> viewModelScope.launch { repository?.setSearchRadiusKm(intent.radiusKm) }
            is SettingsIntent.SetQuickRatingEnabled -> viewModelScope.launch { repository?.setQuickRatingEnabled(intent.enabled) }
            is SettingsIntent.SetAutoUpdatePhonebookServices -> viewModelScope.launch {
                repository?.setAutoUpdatePhonebookServices(intent.enabled)
            }
            is SettingsIntent.SetSmartFilter -> viewModelScope.launch { setSmartFilter(intent.enabled) }
            is SettingsIntent.SetDuplicateScanEnabled -> viewModelScope.launch { repository?.setDuplicateScanEnabled(intent.enabled) }
            is SettingsIntent.SetPostCallCategorizeEnabled -> viewModelScope.launch {
                repository?.setPostCallCategorizeEnabled(intent.enabled)
            }
            is SettingsIntent.SetCrashReportsEnabled -> viewModelScope.launch { repository?.setCrashReportsEnabled(intent.enabled) }
            is SettingsIntent.SetInterestTags -> viewModelScope.launch { repository?.setInterestTags(intent.tags) }
            is SettingsIntent.SetSwipeActions -> viewModelScope.launch { repository?.setSwipeActions(intent.start, intent.end) }
            is SettingsIntent.SetCategoryRingtone -> viewModelScope.launch {
                if (setCategoryRingtoneUseCase != null) {
                    setCategoryRingtoneUseCase(intent.category, intent.uri)
                } else {
                    repository?.setCategoryRingtone(intent.category, intent.uri)
                }
                _uiState.update { it.copy(showRingtonePickerCategory = null) }
            }

            // النسخ الاحتياطي والاستعادة
            is SettingsIntent.ShowExportDialog -> _uiState.update {
                it.copy(showExportPasswordDialog = true, backupErrorMessage = null, backupExportSuccess = false)
            }
            is SettingsIntent.DismissExportDialog -> _uiState.update { it.copy(showExportPasswordDialog = false) }
            is SettingsIntent.ExportBackup -> viewModelScope.launch {
                _uiState.update { it.copy(isExportingBackup = true, backupErrorMessage = null) }
                try {
                    val bytes = exportBackup?.invoke(intent.password)
                        ?: throw IllegalStateException("Backup export not configured")
                    _uiState.update {
                        it.copy(
                            isExportingBackup = false,
                            showExportPasswordDialog = false,
                            backupExportSuccess = true
                        )
                    }
                    sendEffect(SettingsEffect.BackupExported(bytes))
                } catch (err: Throwable) {
                    _uiState.update { it.copy(isExportingBackup = false, backupErrorMessage = err.message) }
                }
            }
            is SettingsIntent.ShowImportDialog -> _uiState.update {
                it.copy(showImportPasswordDialog = true, backupErrorMessage = null)
            }
            is SettingsIntent.DismissImportDialog -> _uiState.update { it.copy(showImportPasswordDialog = false) }
            is SettingsIntent.PreviewBackupFile -> viewModelScope.launch {
                _uiState.update { it.copy(isImportingBackup = true, backupErrorMessage = null) }
                try {
                    val preview = previewBackup?.invoke(intent.cipherBytes, intent.password)
                        ?: throw IllegalStateException("Backup preview not configured")
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            showImportPasswordDialog = false,
                            showRestorePreviewDialog = true,
                            backupPreview = preview
                        )
                    }
                } catch (err: Throwable) {
                    _uiState.update { it.copy(isImportingBackup = false, backupErrorMessage = err.message) }
                }
            }
            is SettingsIntent.ConfirmRestore -> viewModelScope.launch {
                _uiState.update { it.copy(isImportingBackup = true, backupErrorMessage = null) }
                try {
                    val result = restoreBackup?.invoke(
                        cipherBytes = intent.cipherBytes,
                        password = intent.password,
                        mode = intent.mode,
                        restorePreferences = intent.restorePreferences
                    ) ?: throw IllegalStateException("Backup restore not configured")
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            showRestorePreviewDialog = false,
                            restoreResult = result
                        )
                    }
                    sendEffect(SettingsEffect.BackupRestored(result))
                } catch (err: Throwable) {
                    _uiState.update { it.copy(isImportingBackup = false, backupErrorMessage = err.message) }
                }
            }
            is SettingsIntent.DismissRestorePreviewDialog -> _uiState.update {
                it.copy(showRestorePreviewDialog = false, backupPreview = null)
            }
            is SettingsIntent.DismissError -> _uiState.update { it.copy(backupErrorMessage = null) }

            // النغمات
            is SettingsIntent.OpenRingtonePicker -> _uiState.update { it.copy(showRingtonePickerCategory = intent.category) }
            is SettingsIntent.DismissRingtonePicker -> _uiState.update { it.copy(showRingtonePickerCategory = null) }
            is SettingsIntent.ShowRingtoneGuidance -> _uiState.update { it.copy(showRingtoneGuidanceDialog = true) }
            is SettingsIntent.DismissRingtoneGuidance -> _uiState.update { it.copy(showRingtoneGuidanceDialog = false) }
        }
    }
}
