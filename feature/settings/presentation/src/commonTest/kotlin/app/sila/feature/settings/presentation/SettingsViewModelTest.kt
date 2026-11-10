package app.sila.feature.settings.presentation

import app.sila.core.common.AppDispatchers
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.Phone
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.AppLocalePort
import app.sila.feature.settings.domain.ArhamReminderSchedulingPort
import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.BackupDecryptionException
import app.sila.feature.settings.domain.BackupErrorKind
import app.sila.feature.settings.domain.BackupPayload
import app.sila.feature.settings.domain.BackupPreferencesEntry
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.RingtoneItem
import app.sila.feature.settings.domain.SettingsRepository
import app.sila.feature.settings.domain.UnmatchedBackupEntry
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
import kotlin.test.assertContentEquals
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

    /** سجل أحداث مشترك: «الكتابة قبل الجدولة» تُثبت ترتبتها بعد الحقيقة (S2-13 · تذكرة #71). */
    private open class TestSettingsRepository(
        val events: MutableList<String> = mutableListOf()
    ) : SettingsRepository {
        val prefsFlow = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> get() = prefsFlow.asStateFlow()

        override suspend fun setThemeMode(mode: AppThemeMode) { prefsFlow.value = prefsFlow.value.copy(themeMode = mode) }
        override suspend fun setLanguage(language: AppLanguage) { prefsFlow.value = prefsFlow.value.copy(language = language) }
        override suspend fun setDigits(digits: AppDigits) { prefsFlow.value = prefsFlow.value.copy(digits = digits) }
        override suspend fun setNameOrder(order: NameOrder) { prefsFlow.value = prefsFlow.value.copy(nameOrder = order) }
        override suspend fun setDefaultTab(tab: DefaultTab) { prefsFlow.value = prefsFlow.value.copy(defaultTab = tab) }
        override suspend fun setVisibleAccounts(accounts: Set<String>) { prefsFlow.value = prefsFlow.value.copy(visibleAccounts = accounts) }
        override suspend fun setDefaultSimSlot(slot: Int?) { prefsFlow.value = prefsFlow.value.copy(defaultSimSlot = slot) }
        override suspend fun setArhamNotificationsEnabled(enabled: Boolean) {
            prefsFlow.value = prefsFlow.value.copy(arhamNotificationsEnabled = enabled)
            events += "notifications:$enabled"
        }
        override suspend fun setArhamNotificationTime(hour: Int, minute: Int) {
            prefsFlow.value = prefsFlow.value.copy(arhamNotificationHour = hour, arhamNotificationMinute = minute)
            events += "notificationTime:$hour:$minute"
        }
        override suspend fun setArhamMaxPerDay(max: Int) { prefsFlow.value = prefsFlow.value.copy(arhamMaxPerDay = max) }
        override suspend fun setQuietHours(startHour: Int, endHour: Int) {
            prefsFlow.value = prefsFlow.value.copy(quietHoursStartHour = startHour, quietHoursEndHour = endHour)
            events += "quietHours:$startHour-$endHour"
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
        ): RestoreResult = restoreResultOverride ?: RestoreResult(
            matchedCategories = 3,
            unmatchedCategories = 0,
            matchedArham = 2,
            unmatchedArham = 0,
            restoredPreferences = restorePreferences,
            unmatchedEntries = emptyList()
        )

        // S1-15 (تذكرة #62): مسارات الربط اليدوي قابلة للتهيئة لكل اختبار
        var restoreResultOverride: RestoreResult? = null
        var linkCandidates: List<Contact> = emptyList()
        var lastLinkQuery: String? = null
        var linkOutcome: Boolean = true
        var lastLinkEntry: UnmatchedBackupEntry? = null
        var lastLinkTargetId: Long? = null

        override suspend fun searchLinkCandidates(query: String): List<Contact> {
            lastLinkQuery = query
            return linkCandidates
        }

        override suspend fun linkUnmatchedEntry(
            entry: UnmatchedBackupEntry,
            targetContactId: Long
        ): Boolean {
            lastLinkEntry = entry
            lastLinkTargetId = targetContactId
            return linkOutcome
        }
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

    /** مسجّل إعادة الجدولة (تذكرة #71): يعلّم كل نداء في سجل الأحداث المشترك للترتاب. */
    private class RecordingReminderSchedulingPort(
        private val events: MutableList<String>
    ) : ArhamReminderSchedulingPort {
        override suspend fun rescheduleFromNow() {
            events += "reschedule"
        }
    }

    /** S2-14 (تذكرة #68): مستودع يسجّل كتابة اللغة في سجل الأحداث المشترك لتثبيت الترتيب. */
    private class LanguageRecordingRepository(
        events: MutableList<String>
    ) : TestSettingsRepository(events) {
        override suspend fun setLanguage(language: AppLanguage) {
            super.setLanguage(language)
            events += "language:${language.code}"
        }
    }

    /** S2-14 (تذكرة #68): منفذ تطبيق لغة المنصة يسجّل استدعاءه في نفس سجل الأحداث. */
    private class RecordingAppLocalePort(
        private val events: MutableList<String>
    ) : AppLocalePort {
        override suspend fun applyApplicationLocale(language: AppLanguage) {
            events += "applyLocale:${language.code}"
        }
    }

    @Test
    fun reminderPreferenceIntents_rescheduleImmediatelyAfterWrite() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val repo = TestSettingsRepository(events)
        val vm = SettingsViewModel(
            repository = repo,
            reminderSchedulingPort = RecordingReminderSchedulingPort(events),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetArhamNotificationTime(19, 45))
        vm.onIntent(SettingsIntent.SetQuietHours(22, 7))
        vm.onIntent(SettingsIntent.SetArhamNotifications(true))
        settle()

        // S2-13 (تذكرة #71 · ADR-0016): كل نية تُجدول فوراً بعد نجاح كتابتها —
        // بلا انتظار إقلاع لاحق، وكل نية نداءً واحداً بترتيب الحدوث حرفاً.
        assertEquals(
            listOf(
                "notificationTime:19:45", "reschedule",
                "quietHours:22-7", "reschedule",
                "notifications:true", "reschedule",
            ),
            events,
            "الجدولة الفورية تتبع الكتابة مباشرة لكل نية تؤثر على موعد التذكير",
        )
    }

    @Test
    fun setLanguage_appliesApplicationLocaleAfterPreferenceWrite() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val repo = LanguageRecordingRepository(events)
        val vm = SettingsViewModel(
            repository = repo,
            appLocalePort = RecordingAppLocalePort(events),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetLanguage(AppLanguage.ENGLISH))
        settle()

        // S2-14 (تذكرة #68): نية `SetLanguage` تُقيّد التفضيل أولاً وتطبّق لغة المنصة
        // بعده حصراً (نمط ADR-0016 «فور نجاح الكتابة») — بالترتيب ذاته لا معكوسه.
        assertEquals(
            listOf("language:en", "applyLocale:en"),
            events,
            "تطبيق لغة المنصة يتبع نجاح كتابة التفضيل حصراً وبترتيب الحدوث",
        )
        assertEquals(
            AppLanguage.ENGLISH,
            repo.prefsFlow.value.language,
            "التفضيل ذاته قُيّد باللغة الجديدة",
        )
    }

    @Test
    fun unrelatedPreferenceIntents_doNotRescheduleReminder() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val repo = TestSettingsRepository(events)
        val vm = SettingsViewModel(
            repository = repo,
            reminderSchedulingPort = RecordingReminderSchedulingPort(events),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetThemeMode(AppThemeMode.DARK))
        vm.onIntent(SettingsIntent.SetDigits(AppDigits.HINDI))
        settle()

        assertTrue(events.isEmpty(), "تفضيلات لا تمس موعد التذكير لا تُشغّل جدولة إطلاقاً")
    }

    /** S2-12 (تذكرة #69): دورة السمة قرار في الـ VM بترتيب SYSTEM→LIGHT→DARK→SYSTEM كاملاً. */
    @Test
    fun cycleThemeModeStepsThroughAllModesAndBackToSystem() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            repository = repo,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.CycleThemeMode)
        settle()
        assertEquals(AppThemeMode.LIGHT, repo.prefsFlow.value.themeMode)

        vm.onIntent(SettingsIntent.CycleThemeMode)
        settle()
        assertEquals(AppThemeMode.DARK, repo.prefsFlow.value.themeMode)

        vm.onIntent(SettingsIntent.CycleThemeMode)
        settle()
        assertEquals(
            AppThemeMode.SYSTEM,
            repo.prefsFlow.value.themeMode,
            "الدورة الثلاثية تعود إلى نقطة البداية عبر قراءة التفضيل الحي لا نفس الشاشة",
        )
    }

    /** S2-12 (تذكرة #69): لفّ الساعة 24→00 بتصفير الدقيقة السلوك التاريخي + ترتيب الجدولة (تذكرة #71). */
    @Test
    fun cycleArhamNotificationHourWrapsMinuteResetAndReschedulesInOrder() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val repo = TestSettingsRepository(events)
        val vm = SettingsViewModel(
            repository = repo,
            reminderSchedulingPort = RecordingReminderSchedulingPort(events),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetArhamNotificationTime(23, 45))
        settle()
        vm.onIntent(SettingsIntent.CycleArhamNotificationHour)
        settle()

        assertEquals(0, repo.prefsFlow.value.arhamNotificationHour, "لفّ الساعة 23 إلى 00")
        assertEquals(0, repo.prefsFlow.value.arhamNotificationMinute, "السلوك التاريخي: الدقيقة تُصفَّر مع كل دورة")
        assertEquals(
            listOf("notificationTime:23:45", "reschedule", "notificationTime:0:0", "reschedule"),
            events,
            "اللفّ كتابة ثم جدولة فورية بترتيب الحدوث ذاته كما في تذكرة #71",
        )
    }

    /** S2-12 (تذكرة #69): حدود الحد اليومي التاريخية 1..5 محفوظة — عند السقف تعود إلى 1. */
    @Test
    fun cycleArhamMaxPerDayWrapsAtHistoricalBoundary() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            repository = repo,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetArhamMaxPerDay(5))
        settle()
        vm.onIntent(SettingsIntent.CycleArhamMaxPerDay)
        settle()
        assertEquals(1, repo.prefsFlow.value.arhamMaxPerDay, "عند السقف التاريخي 5 تعود الدورة إلى 1")

        vm.onIntent(SettingsIntent.CycleArhamMaxPerDay)
        settle()
        assertEquals(2, repo.prefsFlow.value.arhamMaxPerDay, "قبيل السقف تتقدم الدورة واحداً")
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
    fun restoreWithUnmatchedEntries_opensResultDialog_andManualLinkRemovesEntry() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val unmatched = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = Category.FAMILY,
            arhamRelation = null
        )
        repo.restoreResultOverride = RestoreResult(
            matchedCategories = 2,
            unmatchedCategories = 1,
            matchedArham = 0,
            unmatchedArham = 0,
            restoredPreferences = false,
            unmatchedEntries = listOf(unmatched)
        )
        val candidate = Contact(
            id = 5,
            systemContactId = "sys_5",
            name = ContactName(displayName = "سعاد عبد الرحمن"),
            phones = listOf(Phone(raw = "0100", e164 = "+201000000000"))
        )
        repo.linkCandidates = listOf(candidate)
        val crypto = TestCryptoEngine()
        val vm = SettingsViewModel(
            repository = repo,
            cryptoEngine = crypto,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        // تنبيه: نص JSON صالح مطلوب — RestoreBackupUseCase يفكك الـ payload قبل المستودع
        // (إلا رمى BackupDecryptionException وأخذ مسار الخطأ بلا حوار نتيجة)
        val cipher = crypto.encrypt("""{"version":1,"exportedAt":"now"}""".encodeToByteArray(), "pw")
        vm.onIntent(SettingsIntent.PreviewBackupFile(cipher, "pw"))
        settle()
        vm.onIntent(
            SettingsIntent.ConfirmRestore(
                cipherBytes = cipher,
                password = "pw",
                mode = RestoreMode.MERGE,
                restorePreferences = false
            )
        )
        settle()

        // الحوار يُفتح تلقائياً عند وجود سجلات غير مطابقة (S1-15)
        assertTrue(vm.state.value.showRestoreResultDialog, "Restore result dialog must auto-open when unmatched entries exist")
        assertEquals(listOf(unmatched), vm.state.value.unmatchedRemaining)

        // فتح الربط اليدوي لسجل
        vm.onIntent(SettingsIntent.OpenLinkEntry(unmatched))
        settle()
        assertEquals(unmatched, vm.state.value.linkEntry)

        // استعلام قصير (< 2) لا يستهلك البحث الأساساً
        vm.onIntent(SettingsIntent.SetLinkQuery("س"))
        settle()
        assertEquals(0, vm.state.value.linkCandidates.size)
        assertEquals(null, repo.lastLinkQuery)

        // بحث فعلي مفوَّض للمستودع
        vm.onIntent(SettingsIntent.SetLinkQuery("سعاد"))
        settle()
        assertEquals("سعاد", repo.lastLinkQuery)
        assertEquals(listOf(candidate), vm.state.value.linkCandidates)

        // ربط السجل بجهة موجودة: إزالة من القائمة وفتح الباب للسجل التالي
        vm.onIntent(SettingsIntent.LinkUnmatchedEntry(5))
        settle()
        assertTrue(vm.state.value.unmatchedRemaining.isEmpty(), "Linked entry must be removed from the remaining list")
        assertEquals(1, vm.state.value.unmatchedLinkedCount)
        assertEquals(null, vm.state.value.linkEntry)
        assertFalse(vm.state.value.linkFailed, "Successful link must clear the failure flag")
        assertEquals(unmatched, repo.lastLinkEntry)
        assertEquals(5L, repo.lastLinkTargetId)

        // إغلاق حوار النتيجة يمسح كل حالة الربط
        vm.onIntent(SettingsIntent.DismissRestoreResultDialog)
        settle()
        assertFalse(vm.state.value.showRestoreResultDialog, "Dismiss must close the result dialog")
        assertEquals(0, vm.state.value.unmatchedLinkedCount)
    }

    @Test
    fun linkUnmatchedEntry_failedRepository_marksLinkFailedAndKeepsEntrySelection() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val unmatched = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = Category.WORK,
            arhamRelation = null
        )
        val vm = SettingsViewModel(
            repository = repo,
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.OpenLinkEntry(unmatched))
        settle()
        assertEquals(unmatched, vm.state.value.linkEntry)

        repo.linkOutcome = false
        vm.onIntent(SettingsIntent.LinkUnmatchedEntry(99))
        settle()

        assertTrue(vm.state.value.linkFailed, "Repository failure must surface as linkFailed, not a silent no-op")
        assertEquals(unmatched, vm.state.value.linkEntry)
        assertEquals(0, vm.state.value.unmatchedLinkedCount)
        assertEquals(99L, repo.lastLinkTargetId)
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
    fun setImportBackupBytes_adoptsCappedNonEmptyBytes_andClearsOnDismissImport() = runTest(testDispatcher) {
        val vm = SettingsViewModel(
            repository = TestSettingsRepository(),
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        val bytes = byteArrayOf(1, 2, 3)
        vm.onIntent(SettingsIntent.SetImportBackupBytes(bytes))
        settle()
        assertContentEquals(bytes, vm.state.value.importBackupBytes)

        vm.onIntent(SettingsIntent.DismissImportDialog)
        settle()
        assertEquals(null, vm.state.value.importBackupBytes)
    }

    @Test
    fun setImportBackupBytes_rejectsOversizeAndEmptyAsNoAdoption() = runTest(testDispatcher) {
        val vm = SettingsViewModel(
            repository = TestSettingsRepository(),
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.SetImportBackupBytes(ByteArray(SettingsViewModel.MAX_IMPORT_BACKUP_BYTES + 1)))
        settle()
        assertEquals(null, vm.state.value.importBackupBytes)

        vm.onIntent(SettingsIntent.SetImportBackupBytes(byteArrayOf()))
        settle()
        assertEquals(null, vm.state.value.importBackupBytes)
    }

    @Test
    fun ringtonePicker_dialogState() = runTest(testDispatcher) {
        val repo = TestSettingsRepository()
        val vm = SettingsViewModel(
            repository = repo,
            platformRingtoneSource = TestPlatformRingtoneSource(),
            dispatchers = testDispatchers
        )

        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.onIntent(SettingsIntent.OpenRingtonePicker(Category.FAMILY))
        settle()
        assertEquals(Category.FAMILY, vm.state.value.showRingtonePickerCategory)

        vm.onIntent(SettingsIntent.DismissRingtonePicker)
        settle()
        assertEquals(null, vm.state.value.showRingtonePickerCategory)
    }
}
