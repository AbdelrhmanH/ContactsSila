package app.sila.feature.settings.presentation

import app.sila.core.common.AppDispatchers
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
import app.sila.feature.settings.domain.BackupDecryptionException
import app.sila.feature.settings.domain.BackupErrorKind
import app.sila.feature.settings.domain.BackupPayload
import app.sila.feature.settings.domain.BackupPreferencesEntry
import app.sila.feature.settings.domain.ObserveUserPreferencesUseCase
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.RingtoneItem
import app.sila.feature.settings.domain.SetArhamNotificationsUseCase
import app.sila.feature.settings.domain.SetLanguageUseCase
import app.sila.feature.settings.domain.SetSmartFilterUseCase
import app.sila.feature.settings.domain.SetThemeModeUseCase
import app.sila.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : AppDispatchers {
        override val main = testDispatcher
        override val io = testDispatcher
        override val default = testDispatcher
    }

    private class TestSettingsRepository : SettingsRepository {
        val prefsFlow = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> get() = prefsFlow.asStateFlow()

        override suspend fun setThemeMode(mode: AppThemeMode) { prefsFlow.value = prefsFlow.value.copy(themeMode = mode) }
        override suspend fun setLanguage(language: AppLanguage) { prefsFlow.value = prefsFlow.value.copy(language = language) }
        override suspend fun setDigits(digits: AppDigits) { prefsFlow.value = prefsFlow.value.copy(digits = digits) }
        override suspend fun setNameOrder(order: NameOrder) { prefsFlow.value = prefsFlow.value.copy(nameOrder = order) }
        override suspend fun setDefaultTab(tab: DefaultTab) { prefsFlow.value = prefsFlow.value.copy(defaultTab = tab) }
        override suspend fun setVisibleAccounts(accounts: Set<String>) { prefsFlow.value = prefsFlow.value.copy(visibleAccounts = accounts) }
        override suspend fun setDefaultSimSlot(slot: Int?) { prefsFlow.value = prefsFlow.value.copy(defaultSimSlot = slot) }
        override suspend fun setArhamNotificationsEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(arhamNotificationsEnabled = enabled) }
        override suspend fun setArhamNotificationTime(hour: Int, minute: Int) {
            prefsFlow.value = prefsFlow.value.copy(arhamNotificationHour = hour, arhamNotificationMinute = minute)
        }
        override suspend fun setArhamMaxPerDay(max: Int) { prefsFlow.value = prefsFlow.value.copy(arhamMaxPerDay = max) }
        override suspend fun setQuietHours(startHour: Int, endHour: Int) {
            prefsFlow.value = prefsFlow.value.copy(quietHoursStartHour = startHour, quietHoursEndHour = endHour)
        }
        override suspend fun setDashboardPeriod(period: DashboardPeriod) { prefsFlow.value = prefsFlow.value.copy(dashboardPeriod = period) }
        override suspend fun setConfettiEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(confettiEnabled = enabled) }
        override suspend fun setAutoTrackingEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(autoTrackingEnabled = enabled) }
        override suspend fun setDefaultCity(city: String?) { prefsFlow.value = prefsFlow.value.copy(defaultCity = city) }
        override suspend fun setSearchRadiusKm(radiusKm: Int) { prefsFlow.value = prefsFlow.value.copy(searchRadiusKm = radiusKm) }
        override suspend fun setQuickRatingEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(quickRatingEnabled = enabled) }
        override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(autoUpdatePhonebookServices = enabled) }
        override suspend fun setSmartFilterEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(smartFilterEnabled = enabled) }
        override suspend fun setDuplicateScanEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(duplicateScanEnabled = enabled) }
        override suspend fun setPostCallCategorizeEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(postCallCategorizeEnabled = enabled) }
        override suspend fun setCrashReportsEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(crashReportsEnabled = enabled) }
        override suspend fun setInterestTags(tags: Set<String>) { prefsFlow.value = prefsFlow.value.copy(interestTags = tags) }
        override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) {
            prefsFlow.value = prefsFlow.value.copy(swipeStartAction = start, swipeEndAction = end)
        }
        override suspend fun setCategoryRingtone(category: Category, uri: String?) {
            val updated = if (uri == null) prefsFlow.value.categoryRingtones - category else prefsFlow.value.categoryRingtones + (category to uri)
            prefsFlow.value = prefsFlow.value.copy(categoryRingtones = updated)
        }
        override suspend fun setCategoryRingtones(ringtones: Map<Category, String>) {
            prefsFlow.value = prefsFlow.value.copy(categoryRingtones = ringtones)
        }

        override suspend fun getBackupData(): BackupPayload = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            preferences = BackupPreferencesEntry(language = AppLanguage.ARABIC)
        )

        override suspend fun restoreBackupData(
            payload: BackupPayload,
            mode: RestoreMode,
            restorePreferences: Boolean
        ): RestoreResult = RestoreResult(
            matchedCategories = 3,
            unmatchedCategories = 0,
            matchedArham = 2,
            unmatchedArham = 0,
            restoredPreferences = restorePreferences,
            unmatchedEntries = emptyList()
        )
    }

    private class TestCryptoEngine : BackupCryptoEngine {
        override fun encrypt(plainBytes: ByteArray, password: String): ByteArray =
            ("ENC:" + password + ":" + plainBytes.decodeToString()).encodeToByteArray()

        override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray =
            cipherBytes.decodeToString().removePrefix("ENC:$password:").encodeToByteArray()
    }

    private class TestPlatformRingtoneSource : PlatformRingtoneSource {
        override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) = Unit
        override fun getSystemDefaultRingtoneUri(): String = "content://media/default.ogg"
        override fun getAvailableRingtones(): List<RingtoneItem> = listOf(RingtoneItem("Default", "default.ogg"))
    }

    /** محرك يفشل دائماً في الفك — لمحاكاة كلمة مرور خاطئة/ملف تالف. */
    private class FailingCryptoEngine : BackupCryptoEngine {
        override fun encrypt(plainBytes: ByteArray, password: String): ByteArray = plainBytes
        override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray =
            throw BackupDecryptionException("Corrupted backup: authentication tag mismatch (tampering detected)")
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
        runCurrent()
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialPreferences_flowThroughState() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            observePreferences = ObserveUserPreferencesUseCase(repo),
            setThemeMode = SetThemeModeUseCase(repo),
            setLanguage = SetLanguageUseCase(repo),
            setArhamNotifications = SetArhamNotificationsUseCase(repo),
            setSmartFilter = SetSmartFilterUseCase(repo),
            repository = repo,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        assertEquals(AppThemeMode.SYSTEM, vm.state.value.preferences.themeMode)
        assertEquals(AppLanguage.ARABIC, vm.state.value.preferences.language)
    }

    @Test
    fun t9PreferenceIntents_updateStateCorrectly() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            observePreferences = ObserveUserPreferencesUseCase(repo),
            setThemeMode = SetThemeModeUseCase(repo),
            setLanguage = SetLanguageUseCase(repo),
            setArhamNotifications = SetArhamNotificationsUseCase(repo),
            setSmartFilter = SetSmartFilterUseCase(repo),
            repository = repo,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetDigits(AppDigits.HINDI))
        vm.onIntent(SettingsIntent.SetNameOrder(NameOrder.LAST_FIRST))
        vm.onIntent(SettingsIntent.SetDefaultTab(DefaultTab.SERVICES))
        vm.onIntent(SettingsIntent.SetDashboardPeriod(DashboardPeriod.MONTH))
        vm.onIntent(SettingsIntent.SetQuietHours(22, 7))
        vm.onIntent(SettingsIntent.SetArhamNotificationTime(19, 45))
        vm.onIntent(SettingsIntent.SetSwipeActions(ContactSwipeAction.NONE, ContactSwipeAction.MESSAGE))
        vm.onIntent(SettingsIntent.SetCategoryRingtone(Category.FAMILY, "content://media/family.ogg"))

        settle()

        assertEquals(AppDigits.HINDI, vm.state.value.preferences.digits)
        assertEquals(NameOrder.LAST_FIRST, vm.state.value.preferences.nameOrder)
        assertEquals(DefaultTab.SERVICES, vm.state.value.preferences.defaultTab)
        assertEquals(DashboardPeriod.MONTH, vm.state.value.preferences.dashboardPeriod)
        assertEquals(22, vm.state.value.preferences.quietHoursStartHour)
        assertEquals(7, vm.state.value.preferences.quietHoursEndHour)
        assertEquals(19, vm.state.value.preferences.arhamNotificationHour)
        assertEquals(45, vm.state.value.preferences.arhamNotificationMinute)
        assertEquals(ContactSwipeAction.NONE, vm.state.value.preferences.swipeStartAction)
        assertEquals("content://media/family.ogg", vm.state.value.categoryRingtones[Category.FAMILY])
    }

    @Test
    fun backupExportAndRestore_flowEmitsEffectsAndUpdatesState() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val crypto = TestCryptoEngine()
        val vm = SettingsViewModel(
            repository = repo,
            cryptoEngine = crypto,
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        val effects = mutableListOf<SettingsEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        settle()

        // 1) فتح حوار التصدير
        vm.onIntent(SettingsIntent.ShowExportDialog)
        settle()
        assertTrue(vm.state.value.showExportPasswordDialog)

        // 2) التصدير
        vm.onIntent(SettingsIntent.ExportBackup("SecretPassword123"))
        settle()
        assertFalse(vm.state.value.showExportPasswordDialog)
        assertTrue(vm.state.value.backupExportSuccess)
        assertTrue(effects.any { it is SettingsEffect.BackupExported })

        // 3) المعاينة
        val dummyCipher = crypto.encrypt("""{"version":1,"exportedAt":"now","appVersion":"1.0.0","categories":[{"lookupKey":"k1","displayName":"Ali","category":"FAMILY"}]}""".encodeToByteArray(), "SecretPassword123")
        vm.onIntent(SettingsIntent.PreviewBackupFile(dummyCipher, "SecretPassword123"))
        settle()
        assertTrue(vm.state.value.showRestorePreviewDialog)
        assertEquals(1, vm.state.value.backupPreview?.categoriesCount)

        // 4) التأكيد والاستعادة
        vm.onIntent(
            SettingsIntent.ConfirmRestore(
                cipherBytes = dummyCipher,
                password = "SecretPassword123",
                mode = RestoreMode.MERGE,
                restorePreferences = true
            )
        )
        settle()
        assertFalse(vm.state.value.showRestorePreviewDialog)
        assertNotNull(vm.state.value.restoreResult)
        assertEquals(3, vm.state.value.restoreResult?.matchedCategories)
        assertTrue(effects.any { it is SettingsEffect.BackupRestored })
    }

    @Test
    fun previewFailure_decryptionFailure_mapsToDecryptionFailedKindNotRawMessage() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            repository = repo,
            cryptoEngine = FailingCryptoEngine(),
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.PreviewBackupFile(cipherBytes = byteArrayOf(1, 2, 3), password = "Whatever"))
        settle()

        assertEquals(BackupErrorKind.DECRYPTION_FAILED, vm.state.value.backupErrorKind)
        vm.onIntent(SettingsIntent.DismissError)
        settle()
        assertEquals(null, vm.state.value.backupErrorKind)
    }

    @Test
    fun previewFailure_blankPassword_mapsToBlankPasswordKindNotRawMessage() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val crypto = TestCryptoEngine()
        val vm = SettingsViewModel(
            repository = repo,
            cryptoEngine = crypto,
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.PreviewBackupFile(cipherBytes = "anything".encodeToByteArray(), password = "  "))
        settle()

        assertEquals(BackupErrorKind.BLANK_PASSWORD, vm.state.value.backupErrorKind)
    }

    @Test
    fun ringtoneGuidanceAndPicker_dialogStates() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            repository = repo,
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.ShowRingtoneGuidance)
        settle()
        assertTrue(vm.state.value.showRingtoneGuidanceDialog)

        vm.onIntent(SettingsIntent.DismissRingtoneGuidance)
        settle()
        assertFalse(vm.state.value.showRingtoneGuidanceDialog)

        vm.onIntent(SettingsIntent.OpenRingtonePicker(Category.FAMILY))
        settle()
        assertEquals(Category.FAMILY, vm.state.value.showRingtonePickerCategory)

        vm.onIntent(SettingsIntent.DismissRingtonePicker)
        settle()
        assertEquals(null, vm.state.value.showRingtonePickerCategory)
    }
}
