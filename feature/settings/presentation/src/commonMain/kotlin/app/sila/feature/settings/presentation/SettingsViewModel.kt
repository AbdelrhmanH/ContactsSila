package app.sila.feature.settings.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppDispatchers
import app.sila.core.common.BaseMviViewModel
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.BackupErrorKind
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.RingtoneItem
import app.sila.feature.settings.domain.SettingsBackupPort
import app.sila.feature.settings.domain.SettingsBackupPortImpl
import app.sila.feature.settings.domain.ArhamReminderSchedulingPort
import app.sila.feature.settings.domain.AppLocalePort
import app.sila.feature.settings.domain.SettingsPreferencesPort
import app.sila.feature.settings.domain.SettingsPreferencesPortImpl
import app.sila.feature.settings.domain.SettingsRepository
import app.sila.feature.settings.domain.SettingsRingtonePort
import app.sila.feature.settings.domain.SettingsRingtonePortImpl
import app.sila.feature.settings.domain.UnmatchedBackupEntry
import app.sila.feature.settings.domain.asBackupErrorKind
import kotlinx.coroutines.CancellationException
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
    val backupErrorKind: BackupErrorKind? = null,
    /** بايتات نسخة مختارة من ملف الجهاز (SAF/مضيف Android) عبر نية SetImportBackupBytes — يُتبنّى ضمن سقف 16MB ويُمسح عند إغلاق أو إتمام مسار الاستعادة. */
    val importBackupBytes: ByteArray? = null,
    // حالات الحوارات
    val showExportPasswordDialog: Boolean = false,
    val showImportPasswordDialog: Boolean = false,
    val showRestorePreviewDialog: Boolean = false,
    val showRingtonePickerCategory: Category? = null,
    // حالة حوار نتائج الاستعادة والربط اليدوي لسجلات غير مطابقة (S1-15 — تذكرة #62)
    val showRestoreResultDialog: Boolean = false,
    val unmatchedRemaining: List<UnmatchedBackupEntry> = emptyList(),
    val unmatchedLinkedCount: Int = 0,
    val linkEntry: UnmatchedBackupEntry? = null,
    val linkCandidates: List<Contact> = emptyList(),
    val linkQuery: String = "",
    val linkFailed: Boolean = false
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
    /** بايتات ملف نسخة مختارة من الجهاز (مضيف Android عبر SAF) — الفارغة أو الفائقة السقف لا تُتبنّى. */
    data class SetImportBackupBytes(val bytes: ByteArray?) : SettingsIntent
    data class PreviewBackupFile(val cipherBytes: ByteArray, val password: String) : SettingsIntent
    data class ConfirmRestore(
        val cipherBytes: ByteArray,
        val password: String,
        val mode: RestoreMode,
        val restorePreferences: Boolean
    ) : SettingsIntent
    data object DismissRestorePreviewDialog : SettingsIntent
    // حوارات نتائج الاستعادة والربط اليدوي لسجلات غير مطابقة (S1-15 — تذكرة #62)
    data object DismissRestoreResultDialog : SettingsIntent
    data class OpenLinkEntry(val entry: UnmatchedBackupEntry) : SettingsIntent
    data object DismissLinkEntry : SettingsIntent
    data class SetLinkQuery(val query: String) : SettingsIntent
    data class LinkUnmatchedEntry(val targetContactId: Long) : SettingsIntent
    data object DismissError : SettingsIntent

    // حوارات النغمات
    data class OpenRingtonePicker(val category: Category) : SettingsIntent
    data object DismissRingtonePicker : SettingsIntent
}

sealed interface SettingsEffect {
    data class BackupExported(val bytes: ByteArray) : SettingsEffect
    data class BackupRestored(val result: RestoreResult) : SettingsEffect
    data class ShowToast(val message: String) : SettingsEffect
    data object OpenIosContactsApp : SettingsEffect
}

/**
 * ViewModel ميزة الإعدادات والتفضيلات — يعتمد بوابات نطاق ضيقة ([SettingsPreferencesPort]
 * و[SettingsBackupPort] و[SettingsRingtonePort] و[ArhamReminderSchedulingPort]) فتمر كل
 * كتابة عبر UseCase حقيقية مرة واحدة (DIP حقيقي بلا مسار `useCase ?: repository` مزدوج)
 * ويحول التدفقات التفاعلية إلى StateFlow (CODING_GUIDELINES.md §1, §8 · ADR-0014).
 */
class SettingsViewModel(
    private val preferencesPort: SettingsPreferencesPort,
    private val backupPort: SettingsBackupPort,
    private val ringtonePort: SettingsRingtonePort,
    /**
     * منفذ إعادة الجدولة الفورية للتذكير (S2-13 · تذكرة #71 · ADR-0016): كتابةٌ تغيّر
     * موعد تذكير أرحام (الميزة/الوقت/ساعات الهدوء) يعقبها `rescheduleFromNow()` فوراً
     * دون انتظار إقلاع لاحق. اختياري بالحقن — كومة التركيب الحقيقية (Koin) تربطه دائماً،
     * والبناء المريح (الاختبارات) يُغني عنه حين لا يفحص المسار.
     */
    private val reminderSchedulingPort: ArhamReminderSchedulingPort? = null,
    /**
     * منفذ تطبيق لغة المنصة (S2-14 · تذكرة #68): نية `SetLanguage` يعقبها تطبيق
     * per-app locales فور نجاح كتابة التفضيل — فتقود الموارد كلها لا شاشة الإعدادات
     * حصراً. اختياري بالحقن بنمط منفذ الجدولة أعلاه؛ iOS تنفيذه كائن سلبي.
     */
    private val appLocalePort: AppLocalePort? = null,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) : BaseMviViewModel<SettingsUiState, SettingsIntent, SettingsEffect>(SettingsUiState()) {

    /**
     * منفذ التركيب العملَي (نمط ADR-0014 §3): يبني البوابات الثلاث فوق المستودع
     * ومحرك التشفير الاختياري ومصدر النغمات المنصوي الاختياري.
     */
    constructor(
        repository: SettingsRepository,
        cryptoEngine: BackupCryptoEngine? = null,
        platformRingtoneSource: PlatformRingtoneSource? = null,
        reminderSchedulingPort: ArhamReminderSchedulingPort? = null,
        appLocalePort: AppLocalePort? = null,
        dispatchers: AppDispatchers = DefaultAppDispatchers()
    ) : this(
        preferencesPort = SettingsPreferencesPortImpl(repository),
        backupPort = SettingsBackupPortImpl(repository, cryptoEngine, dispatchers),
        ringtonePort = SettingsRingtonePortImpl(repository, platformRingtoneSource),
        reminderSchedulingPort = reminderSchedulingPort,
        appLocalePort = appLocalePort,
        dispatchers = dispatchers
    )

    private val _uiState = MutableStateFlow(SettingsUiState())

    init {
        viewModelScope.launch(dispatchers.io) {
            val ringtones = ringtonePort.availableRingtones()
            if (ringtones.isNotEmpty()) {
                _uiState.update { it.copy(availableRingtones = ringtones) }
            }
        }
    }

    override val state: StateFlow<SettingsUiState> = combine(
        preferencesPort.observe(),
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
            is SettingsIntent.SetThemeMode -> viewModelScope.launch { preferencesPort.setThemeMode(intent.mode) }
            is SettingsIntent.SetLanguage -> viewModelScope.launch {
                preferencesPort.setLanguage(intent.language)
                // S2-14 (تذكرة #68): تفضيل اللغة تُقيَّد أولاً — وتطبيقه على موارد
                // المنصة (per-app locales على أندرويد) يتبع نجاح الكتابة حصراً
                // (نمط ADR-0016 «فور نجاح الكتابة»).
                appLocalePort?.applyApplicationLocale(intent.language)
            }
            is SettingsIntent.SetDigits -> viewModelScope.launch { preferencesPort.setDigits(intent.digits) }
            is SettingsIntent.SetNameOrder -> viewModelScope.launch { preferencesPort.setNameOrder(intent.order) }
            is SettingsIntent.SetDefaultTab -> viewModelScope.launch { preferencesPort.setDefaultTab(intent.tab) }
            is SettingsIntent.SetVisibleAccounts -> viewModelScope.launch { preferencesPort.setVisibleAccounts(intent.accounts) }
            is SettingsIntent.SetDefaultSimSlot -> viewModelScope.launch { preferencesPort.setDefaultSimSlot(intent.slot) }
            is SettingsIntent.SetArhamNotifications -> viewModelScope.launch {
                preferencesPort.setArhamNotificationsEnabled(intent.enabled)
                // S2-13 (تذكرة #71 · ADR-0016): تعديل الميزة يؤثر على الموعد والمعلّق —
                // إعادة الجدولة فوراً من التفضيل الجديد (iOS: إلغاء المعلّق عند التعطيل).
                reminderSchedulingPort?.rescheduleFromNow()
            }
            is SettingsIntent.SetArhamNotificationTime -> viewModelScope.launch {
                preferencesPort.setArhamNotificationTime(intent.hour, intent.minute)
                // S2-13: الوقت المفضل يشكل الموعد القادم حتماً — جدولة فورية (تذكرة #71).
                reminderSchedulingPort?.rescheduleFromNow()
            }
            is SettingsIntent.SetArhamMaxPerDay -> viewModelScope.launch { preferencesPort.setArhamMaxPerDay(intent.max) }
            is SettingsIntent.SetQuietHours -> viewModelScope.launch {
                preferencesPort.setQuietHours(intent.startHour, intent.endHour)
                // S2-13: ساعات الهدوء تدفع الموعد (أو عبر منتصف الليل) — جدولة فورية (تذكرة #71).
                reminderSchedulingPort?.rescheduleFromNow()
            }
            is SettingsIntent.SetDashboardPeriod -> viewModelScope.launch {
                preferencesPort.setDashboardPeriod(intent.period)
            }
            is SettingsIntent.SetConfettiEnabled -> viewModelScope.launch {
                preferencesPort.setConfettiEnabled(intent.enabled)
            }
            is SettingsIntent.SetAutoTrackingEnabled -> viewModelScope.launch {
                preferencesPort.setAutoTrackingEnabled(intent.enabled)
            }
            is SettingsIntent.SetDefaultCity -> viewModelScope.launch { preferencesPort.setDefaultCity(intent.city) }
            is SettingsIntent.SetSearchRadiusKm -> viewModelScope.launch {
                preferencesPort.setSearchRadiusKm(intent.radiusKm)
            }
            is SettingsIntent.SetQuickRatingEnabled -> viewModelScope.launch {
                preferencesPort.setQuickRatingEnabled(intent.enabled)
            }
            is SettingsIntent.SetAutoUpdatePhonebookServices -> viewModelScope.launch {
                preferencesPort.setAutoUpdatePhonebookServices(intent.enabled)
            }
            is SettingsIntent.SetSmartFilter -> viewModelScope.launch {
                preferencesPort.setSmartFilterEnabled(intent.enabled)
            }
            is SettingsIntent.SetDuplicateScanEnabled -> viewModelScope.launch {
                preferencesPort.setDuplicateScanEnabled(intent.enabled)
            }
            is SettingsIntent.SetPostCallCategorizeEnabled -> viewModelScope.launch {
                preferencesPort.setPostCallCategorizeEnabled(intent.enabled)
            }
            is SettingsIntent.SetCrashReportsEnabled -> viewModelScope.launch {
                preferencesPort.setCrashReportsEnabled(intent.enabled)
            }
            is SettingsIntent.SetInterestTags -> viewModelScope.launch { preferencesPort.setInterestTags(intent.tags) }
            is SettingsIntent.SetSwipeActions -> viewModelScope.launch {
                preferencesPort.setSwipeActions(intent.start, intent.end)
            }
            is SettingsIntent.SetCategoryRingtone -> viewModelScope.launch {
                ringtonePort.setCategoryRingtone(intent.category, intent.uri)
                _uiState.update { it.copy(showRingtonePickerCategory = null) }
            }

            // النسخ الاحتياطي والاستعادة
            is SettingsIntent.ShowExportDialog -> _uiState.update {
                it.copy(showExportPasswordDialog = true, backupErrorKind = null, backupExportSuccess = false)
            }
            is SettingsIntent.DismissExportDialog -> _uiState.update { it.copy(showExportPasswordDialog = false) }
            is SettingsIntent.ExportBackup -> viewModelScope.launch {
                _uiState.update { it.copy(isExportingBackup = true, backupErrorKind = null) }
                try {
                    val bytes = backupPort.exportBackup(intent.password)
                    _uiState.update {
                        it.copy(
                            isExportingBackup = false,
                            showExportPasswordDialog = false,
                            backupExportSuccess = true
                        )
                    }
                    sendEffect(SettingsEffect.BackupExported(bytes))
                } catch (err: Exception) {
                    if (err is CancellationException) throw err
                    _uiState.update { it.copy(isExportingBackup = false, backupErrorKind = err.asBackupErrorKind()) }
                }
            }
            is SettingsIntent.ShowImportDialog -> _uiState.update {
                it.copy(showImportPasswordDialog = true, backupErrorKind = null)
            }
            is SettingsIntent.DismissImportDialog -> _uiState.update {
                it.copy(showImportPasswordDialog = false, importBackupBytes = null)
            }
            // المضيف يسبق بسقف القراءة؛ الـ ViewModel يفحص السقف كحماية ثانية (فارغ/فائق ⇒ لا تبنّي).
            is SettingsIntent.SetImportBackupBytes -> _uiState.update {
                val bytes = intent.bytes
                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMPORT_BACKUP_BYTES) {
                    it.copy(importBackupBytes = null)
                } else {
                    it.copy(importBackupBytes = bytes, backupErrorKind = null)
                }
            }
            is SettingsIntent.PreviewBackupFile -> viewModelScope.launch {
                _uiState.update { it.copy(isImportingBackup = true, backupErrorKind = null) }
                try {
                    val preview = backupPort.previewBackup(intent.cipherBytes, intent.password)
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            showImportPasswordDialog = false,
                            showRestorePreviewDialog = true,
                            backupPreview = preview
                        )
                    }
                } catch (err: Exception) {
                    if (err is CancellationException) throw err
                    _uiState.update { it.copy(isImportingBackup = false, backupErrorKind = err.asBackupErrorKind()) }
                }
            }
            is SettingsIntent.ConfirmRestore -> viewModelScope.launch {
                _uiState.update { it.copy(isImportingBackup = true, backupErrorKind = null) }
                try {
                    val result = backupPort.restoreBackup(
                        cipherBytes = intent.cipherBytes,
                        password = intent.password,
                        mode = intent.mode,
                        restorePreferences = intent.restorePreferences
                    )
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            showRestorePreviewDialog = false,
                            restoreResult = result,
                            importBackupBytes = null,
                            // S1-15 (تذكرة #62): سجلات غير مطابقة تُبرز فوراً بعد الاستعادة
                            // للربط اليدوي بدل بقاءها رقماً صامتاً في الإشعار فقط.
                            unmatchedRemaining = result.unmatchedEntries,
                            unmatchedLinkedCount = 0,
                            showRestoreResultDialog = result.unmatchedEntries.isNotEmpty()
                        )
                    }
                    sendEffect(SettingsEffect.BackupRestored(result))
                } catch (err: Exception) {
                    if (err is CancellationException) throw err
                    _uiState.update { it.copy(isImportingBackup = false, backupErrorKind = err.asBackupErrorKind()) }
                }
            }
            is SettingsIntent.DismissRestorePreviewDialog -> _uiState.update {
                it.copy(showRestorePreviewDialog = false, backupPreview = null, importBackupBytes = null)
            }
            // نتائج الاستعادة والربط اليدوي (S1-15 — تذكرة #62)
            is SettingsIntent.DismissRestoreResultDialog -> _uiState.update {
                it.copy(
                    showRestoreResultDialog = false,
                    unmatchedRemaining = emptyList(),
                    unmatchedLinkedCount = 0,
                    linkEntry = null,
                    linkCandidates = emptyList(),
                    linkQuery = "",
                    linkFailed = false
                )
            }
            is SettingsIntent.OpenLinkEntry -> _uiState.update {
                it.copy(linkEntry = intent.entry, linkCandidates = emptyList(), linkQuery = "", linkFailed = false)
            }
            is SettingsIntent.DismissLinkEntry -> _uiState.update {
                it.copy(linkEntry = null, linkCandidates = emptyList(), linkQuery = "", linkFailed = false)
            }
            is SettingsIntent.SetLinkQuery -> viewModelScope.launch {
                val candidates = backupPort.searchLinkCandidates(intent.query)
                _uiState.update {
                    it.copy(linkQuery = intent.query, linkCandidates = candidates, linkFailed = false)
                }
            }
            is SettingsIntent.LinkUnmatchedEntry -> viewModelScope.launch {
                val entry = _uiState.value.linkEntry
                if (entry == null) {
                    _uiState.update { it.copy(linkFailed = true) }
                    return@launch
                }
                val linked = backupPort.linkUnmatchedEntry(entry, intent.targetContactId)
                if (linked) {
                    _uiState.update {
                        it.copy(
                            unmatchedRemaining = it.unmatchedRemaining.filterNot { remaining -> remaining == entry },
                            unmatchedLinkedCount = it.unmatchedLinkedCount + 1,
                            linkEntry = null,
                            linkCandidates = emptyList(),
                            linkQuery = "",
                            linkFailed = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(linkFailed = true) }
                }
            }
            is SettingsIntent.DismissError -> _uiState.update { it.copy(backupErrorKind = null) }

            // النغمات
            is SettingsIntent.OpenRingtonePicker -> _uiState.update { it.copy(showRingtonePickerCategory = intent.category) }
            is SettingsIntent.DismissRingtonePicker -> _uiState.update { it.copy(showRingtonePickerCategory = null) }
        }
    }

    companion object {
        /** سقف بايتات ملف استيراد النسخة (16MB) — يوافق حد المضيف في AndroidApp تفادياً لتضخّم الذاكرة. */
        const val MAX_IMPORT_BACKUP_BYTES = 16 * 1024 * 1024
    }
}
