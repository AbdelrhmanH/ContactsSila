package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.Relation
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsUseCasesTest {

    private class FakeCryptoEngine : BackupCryptoEngine {
        var encryptCalled = false
        var decryptCalled = false

        override fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
            encryptCalled = true
            return ("ENC:" + password + ":" + plainBytes.decodeToString()).encodeToByteArray()
        }

        override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray {
            decryptCalled = true
            val str = cipherBytes.decodeToString()
            val prefix = "ENC:$password:"
            if (!str.startsWith(prefix)) {
                throw BackupDecryptionException("Wrong password or corrupted header")
            }
            return str.removePrefix(prefix).encodeToByteArray()
        }
    }

    private class FakePlatformRingtoneSource : PlatformRingtoneSource {
        val appliedCategories = mutableListOf<Pair<Category, String?>>()
        var defaultRingtone = "content://media/system_default.ogg"

        override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) {
            appliedCategories += category to ringtoneUri
        }

        override fun getSystemDefaultRingtoneUri(): String = defaultRingtone

        override fun getAvailableRingtones(): List<RingtoneItem> = listOf(
            RingtoneItem("Default", defaultRingtone),
            RingtoneItem("Chime", "content://media/chime.ogg")
        )
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = _prefs.asStateFlow()

        var backupPayloadToReturn = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = listOf(
                BackupCategoryEntry("lk_1", "أبي", Category.FAMILY),
                BackupCategoryEntry("lk_2", "صديقي", Category.FRIENDS)
            ),
            arham = listOf(
                BackupArhamEntry("lk_1", Relation.FATHER, ArhamFrequency.DAILY)
            ),
            tags = listOf(
                BackupTagEntry("lk_1", listOf("عائلة"))
            ),
            preferences = BackupPreferencesEntry(language = AppLanguage.ARABIC)
        )

        var lastRestoredPayload: BackupPayload? = null
        var lastRestoreMode: RestoreMode? = null
        var lastRestorePreferencesFlag: Boolean? = null

        override suspend fun setThemeMode(mode: AppThemeMode) { _prefs.value = _prefs.value.copy(themeMode = mode) }
        override suspend fun setLanguage(language: AppLanguage) { _prefs.value = _prefs.value.copy(language = language) }
        override suspend fun setDigits(digits: AppDigits) { _prefs.value = _prefs.value.copy(digits = digits) }
        override suspend fun setNameOrder(order: NameOrder) { _prefs.value = _prefs.value.copy(nameOrder = order) }
        override suspend fun setDefaultTab(tab: DefaultTab) { _prefs.value = _prefs.value.copy(defaultTab = tab) }
        override suspend fun setVisibleAccounts(accounts: Set<String>) { _prefs.value = _prefs.value.copy(visibleAccounts = accounts) }
        override suspend fun setDefaultSimSlot(slot: Int?) { _prefs.value = _prefs.value.copy(defaultSimSlot = slot) }
        override suspend fun setArhamNotificationsEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(arhamNotificationsEnabled = enabled) }
        override suspend fun setArhamNotificationTime(hour: Int, minute: Int) {
            _prefs.value = _prefs.value.copy(arhamNotificationHour = hour, arhamNotificationMinute = minute)
        }
        override suspend fun setArhamMaxPerDay(max: Int) { _prefs.value = _prefs.value.copy(arhamMaxPerDay = max) }
        override suspend fun setQuietHours(startHour: Int, endHour: Int) {
            _prefs.value = _prefs.value.copy(quietHoursStartHour = startHour, quietHoursEndHour = endHour)
        }
        override suspend fun setDashboardPeriod(period: DashboardPeriod) { _prefs.value = _prefs.value.copy(dashboardPeriod = period) }
        override suspend fun setConfettiEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(confettiEnabled = enabled) }
        override suspend fun setAutoTrackingEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(autoTrackingEnabled = enabled) }
        override suspend fun setDefaultCity(city: String?) { _prefs.value = _prefs.value.copy(defaultCity = city) }
        override suspend fun setSearchRadiusKm(radiusKm: Int) { _prefs.value = _prefs.value.copy(searchRadiusKm = radiusKm) }
        override suspend fun setQuickRatingEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(quickRatingEnabled = enabled) }
        override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) { _prefs.value = _prefs.value.copy(autoUpdatePhonebookServices = enabled) }
        override suspend fun setSmartFilterEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(smartFilterEnabled = enabled) }
        override suspend fun setDuplicateScanEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(duplicateScanEnabled = enabled) }
        override suspend fun setPostCallCategorizeEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(postCallCategorizeEnabled = enabled) }
        override suspend fun setCrashReportsEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(crashReportsEnabled = enabled) }
        override suspend fun setInterestTags(tags: Set<String>) { _prefs.value = _prefs.value.copy(interestTags = tags) }
        override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) {
            _prefs.value = _prefs.value.copy(swipeStartAction = start, swipeEndAction = end)
        }
        override suspend fun setCategoryRingtone(category: Category, uri: String?) {
            val updated = if (uri == null) _prefs.value.categoryRingtones - category else _prefs.value.categoryRingtones + (category to uri)
            _prefs.value = _prefs.value.copy(categoryRingtones = updated)
        }
        override suspend fun setCategoryRingtones(ringtones: Map<Category, String>) {
            _prefs.value = _prefs.value.copy(categoryRingtones = ringtones)
        }

        override suspend fun getBackupData(): BackupPayload = backupPayloadToReturn

        override suspend fun restoreBackupData(
            payload: BackupPayload,
            mode: RestoreMode,
            restorePreferences: Boolean
        ): RestoreResult {
            lastRestoredPayload = payload
            lastRestoreMode = mode
            lastRestorePreferencesFlag = restorePreferences
            return RestoreResult(
                matchedCategories = 2,
                unmatchedCategories = 0,
                matchedArham = 1,
                unmatchedArham = 0,
                restoredPreferences = restorePreferences,
                unmatchedEntries = emptyList()
            )
        }

        // تذكرة #66: مسارات الربط اليدوي أصبحت UseCases (SearchLinkCandidatesUseCase/
        // LinkUnmatchedEntryUseCase) — الـ fake قابل للتهيئة لكل اختبار مع تسجيل المناديات.
        var lastLinkCandidatesQuery: String? = null
        var linkCandidatesToReturn: List<Contact> = emptyList()
        var lastLinkEntry: UnmatchedBackupEntry? = null
        var lastLinkTargetId: Long? = null
        var linkOutcome: Boolean = false

        override suspend fun searchLinkCandidates(query: String): List<Contact> {
            lastLinkCandidatesQuery = query
            return linkCandidatesToReturn
        }

        override suspend fun linkUnmatchedEntry(entry: UnmatchedBackupEntry, targetContactId: Long): Boolean {
            lastLinkEntry = entry
            lastLinkTargetId = targetContactId
            return linkOutcome
        }
    }

    @Test
    fun exportAndPreviewBackup_roundTrip_producesExpectedPreview() = runTest {
        val repo = FakeSettingsRepository()
        val crypto = FakeCryptoEngine()
        val exportUseCase = ExportBackupUseCase(repo, crypto)
        val previewUseCase = PreviewBackupUseCase(crypto)

        val encryptedBytes = exportUseCase("ValidPass#123")
        assertTrue(crypto.encryptCalled)

        val preview = previewUseCase(encryptedBytes, "ValidPass#123")
        assertEquals(2, preview.categoriesCount)
        assertEquals(1, preview.arhamCount)
        assertEquals(1, preview.tagsCount)
        assertTrue(preview.hasPreferences)
        assertEquals("1.0.0", preview.appVersion)
    }

    @Test
    fun previewBackup_withWrongPassword_throwsBackupDecryptionException() = runTest {
        val repo = FakeSettingsRepository()
        val crypto = FakeCryptoEngine()
        val exportUseCase = ExportBackupUseCase(repo, crypto)
        val previewUseCase = PreviewBackupUseCase(crypto)

        val encryptedBytes = exportUseCase("CorrectPassword")
        assertFailsWith<BackupDecryptionException> {
            previewUseCase(encryptedBytes, "WrongPassword")
        }
    }

    @Test
    fun restoreBackup_passesPayloadAndFlagsToRepository() = runTest {
        val repo = FakeSettingsRepository()
        val crypto = FakeCryptoEngine()
        val exportUseCase = ExportBackupUseCase(repo, crypto)
        val restoreUseCase = RestoreBackupUseCase(repo, crypto)

        val encryptedBytes = exportUseCase("SecretKey")
        val result = restoreUseCase(
            cipherBytes = encryptedBytes,
            password = "SecretKey",
            mode = RestoreMode.REPLACE,
            restorePreferences = true
        )

        assertEquals(2, result.matchedCategories)
        assertTrue(result.restoredPreferences)
        assertEquals(RestoreMode.REPLACE, repo.lastRestoreMode)
        assertTrue(repo.lastRestorePreferencesFlag == true)
        assertEquals(2, repo.lastRestoredPayload?.categories?.size)
    }

    @Test
    fun ringtonePriority_contact_over_category_over_system() = runTest {
        val repo = FakeSettingsRepository()
        val platformRingtone = FakePlatformRingtoneSource()
        val getEffectiveRingtone = GetEffectiveRingtoneUseCase(repo, platformRingtone)

        // الحالة 1: لا جهة اتصال ولا تصنيف -> نغمة النظام
        val ringtone1 = getEffectiveRingtone(individualRingtoneUri = null, category = Category.FAMILY)
        assertEquals("content://media/system_default.ogg", ringtone1)

        // الحالة 2: يوجد نغمة تصنيف ولا توجد نغمة جهة اتصال -> نغمة التصنيف
        repo.setCategoryRingtone(Category.FAMILY, "content://media/family_ringtone.ogg")
        val ringtone2 = getEffectiveRingtone(individualRingtoneUri = null, category = Category.FAMILY)
        assertEquals("content://media/family_ringtone.ogg", ringtone2)

        // الحالة 3: يوجد نغمة جهة اتصال فردية -> أولوية لجهة الاتصال على نغمة التصنيف
        val ringtone3 = getEffectiveRingtone(individualRingtoneUri = "content://media/special_person.ogg", category = Category.FAMILY)
        assertEquals("content://media/special_person.ogg", ringtone3)
    }

    @Test
    fun setCategoryRingtone_updatesRepositoryAndPropagatesToPlatform() = runTest {
        val repo = FakeSettingsRepository()
        val platformRingtone = FakePlatformRingtoneSource()
        val setCategoryRingtone = SetCategoryRingtoneUseCase(repo, platformRingtone)

        setCategoryRingtone(Category.FRIENDS, "content://media/friends.ogg")

        val currentRingtone = repo.preferences.first().categoryRingtones[Category.FRIENDS]
        assertEquals("content://media/friends.ogg", currentRingtone)
        assertEquals(1, platformRingtone.appliedCategories.size)
        assertEquals(Category.FRIENDS to "content://media/friends.ogg", platformRingtone.appliedCategories.first())
    }

    @Test
    fun individualPreferenceUseCases_updateRepositoryState() = runTest {
        val repo = FakeSettingsRepository()

        SetDigitsUseCase(repo)(AppDigits.HINDI)
        assertEquals(AppDigits.HINDI, repo.preferences.first().digits)

        SetNameOrderUseCase(repo)(NameOrder.LAST_FIRST)
        assertEquals(NameOrder.LAST_FIRST, repo.preferences.first().nameOrder)

        SetSwipeActionsUseCase(repo)(ContactSwipeAction.NONE, ContactSwipeAction.CALL)
        assertEquals(ContactSwipeAction.NONE, repo.preferences.first().swipeStartAction)
        assertEquals(ContactSwipeAction.CALL, repo.preferences.first().swipeEndAction)

        SetQuietHoursUseCase(repo)(22, 7)
        assertEquals(22, repo.preferences.first().quietHoursStartHour)
        assertEquals(7, repo.preferences.first().quietHoursEndHour)

        SetArhamNotificationTimeUseCase(repo)(18, 30)
        assertEquals(18, repo.preferences.first().arhamNotificationHour)
        assertEquals(30, repo.preferences.first().arhamNotificationMinute)

        SetDashboardPeriodUseCase(repo)(DashboardPeriod.MONTH)
        assertEquals(DashboardPeriod.MONTH, repo.preferences.first().dashboardPeriod)

        SetAutoTrackingEnabledUseCase(repo)(false)
        assertEquals(false, repo.preferences.first().autoTrackingEnabled)

        SetDuplicateScanUseCase(repo)(false)
        assertEquals(false, repo.preferences.first().duplicateScanEnabled)

        SetCrashReportsUseCase(repo)(false)
        assertEquals(false, repo.preferences.first().crashReportsEnabled)
    }

    @Test
    fun preferenceUseCases_addedForDipRefactor_updateRepositoryState() = runTest {
        val repo = FakeSettingsRepository()

        SetDefaultTabUseCase(repo)(DefaultTab.ARHAM)
        assertEquals(DefaultTab.ARHAM, repo.preferences.first().defaultTab)

        SetVisibleAccountsUseCase(repo)(setOf("acme", "work"))
        assertEquals(setOf("acme", "work"), repo.preferences.first().visibleAccounts)

        SetDefaultSimSlotUseCase(repo)(1)
        assertEquals(1, repo.preferences.first().defaultSimSlot)

        SetConfettiEnabledUseCase(repo)(false)
        assertEquals(false, repo.preferences.first().confettiEnabled)

        SetDefaultCityUseCase(repo)("القاهرة")
        assertEquals("القاهرة", repo.preferences.first().defaultCity)

        SetSearchRadiusKmUseCase(repo)(15)
        assertEquals(15, repo.preferences.first().searchRadiusKm)

        SetQuickRatingEnabledUseCase(repo)(false)
        assertEquals(false, repo.preferences.first().quickRatingEnabled)

        SetAutoUpdatePhonebookServicesUseCase(repo)(true)
        assertEquals(true, repo.preferences.first().autoUpdatePhonebookServices)

        SetPostCallCategorizeEnabledUseCase(repo)(true)
        assertEquals(true, repo.preferences.first().postCallCategorizeEnabled)

        SetInterestTagsUseCase(repo)(setOf("عائلة", "العمل"))
        assertEquals(setOf("عائلة", "العمل"), repo.preferences.first().interestTags)
    }

    @Test
    fun searchLinkCandidatesUseCase_shortQuery_returnsEmptyWithoutTouchingRepository() = runTest {
        val repo = FakeSettingsRepository()
        val useCase = SearchLinkCandidatesUseCase(repo)

        assertEquals(0, useCase("").size)
        assertEquals(0, useCase("  ").size)
        assertEquals(0, useCase("س").size)

        // الحارس يمنع المناداة أصلاً — لا استعلام وصل المستودع
        assertEquals(null, repo.lastLinkCandidatesQuery)
    }

    @Test
    fun searchLinkCandidatesUseCase_validQuery_delegatesToRepository() = runTest {
        val repo = FakeSettingsRepository()
        val candidate = Contact(
            id = 5,
            systemContactId = "sys_5",
            name = ContactName(displayName = "سعاد"),
            phones = emptyList()
        )
        repo.linkCandidatesToReturn = listOf(candidate)
        val useCase = SearchLinkCandidatesUseCase(repo)

        val candidates = useCase("سعاد")

        assertEquals("سعاد", repo.lastLinkCandidatesQuery)
        assertEquals(listOf(candidate), candidates)
    }

    @Test
    fun linkUnmatchedEntryUseCase_delegatesAndPropagatesRepositoryOutcome() = runTest {
        val repo = FakeSettingsRepository()
        val useCase = LinkUnmatchedEntryUseCase(repo)
        val entry = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = Category.FAMILY,
            arhamRelation = null
        )

        // الافتراضي: فشل معلوم (false) — يُمرَّر بمصداقية
        assertEquals(false, useCase(entry, targetContactId = 7))
        assertEquals(entry, repo.lastLinkEntry)
        assertEquals(7L, repo.lastLinkTargetId)

        // نجاح المستودع يُمرَّر كما هو
        repo.linkOutcome = true
        assertEquals(true, useCase(entry, targetContactId = 8))
        assertEquals(8L, repo.lastLinkTargetId)
    }
}
