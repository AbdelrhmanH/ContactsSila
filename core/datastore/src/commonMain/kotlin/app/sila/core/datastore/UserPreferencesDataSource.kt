package app.sila.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.TopFiveSelection
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * مصدر بيانات التفضيلات والإعدادات المشترك (PRD §12).
 * يدعم التخزين الفعلي على القرص عبر Jetpack DataStore Preferences (`.preferences_pb`)
 * مع حماية كاملة ضد تعدد مثيلات DataStore لنفس الملف (Singleton Registry per Path).
 */
interface UserPreferencesDataSource {
    val preferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: AppThemeMode)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setDigits(digits: AppDigits)
    suspend fun setNameOrder(order: NameOrder)
    suspend fun setDefaultTab(tab: DefaultTab)
    suspend fun setVisibleAccounts(accounts: Set<String>)
    suspend fun setDefaultSimSlot(slot: Int?)
    suspend fun setArhamNotificationsEnabled(enabled: Boolean)
    suspend fun setQuietHours(startHour: Int, endHour: Int)
    /** الوقت المفضل للتذكير اليومي بالتوقيت المحلي (§M — T8-08). */
    suspend fun setArhamNotificationTime(hour: Int, minute: Int)
    suspend fun setArhamMaxPerDay(max: Int)
    /** تنبيه اليوم السابق للمناسبة (§M «اختيارياً» — T8-08). */
    suspend fun setArhamOccasionDayBeforeEnabled(enabled: Boolean)
    suspend fun setDashboardPeriod(period: DashboardPeriod)
    suspend fun setConfettiEnabled(enabled: Boolean)
    suspend fun setAutoTrackingEnabled(enabled: Boolean)
    suspend fun setDefaultCity(city: String?)
    suspend fun setSearchRadiusKm(radiusKm: Int)
    suspend fun setQuickRatingEnabled(enabled: Boolean)
    suspend fun setAutoUpdatePhonebookServices(enabled: Boolean)
    suspend fun setSmartFilterEnabled(enabled: Boolean)
    suspend fun setDuplicateScanEnabled(enabled: Boolean)
    suspend fun setPostCallCategorizeEnabled(enabled: Boolean)
    suspend fun setCrashReportsEnabled(enabled: Boolean)
    suspend fun setInterestTags(tags: Set<String>)
    suspend fun setCategoryRingtone(category: Category, uri: String?)
    suspend fun setCategoryRingtones(ringtones: Map<Category, String>)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setIsGuest(isGuest: Boolean)
    suspend fun setOnboardingResumeStep(step: String?)
    suspend fun setOnboardingContactsPermission(permission: String?)
    suspend fun setOnboardingTopFiveSelections(selections: List<TopFiveSelection>)

    /**
     * رفع علامة إنجاز تجسيد «أهم 5» لمرة واحدة (T8-04 · §F.10) — بلا مسار تصفير:
     * العلامة لا تُعاد فتحها أبداً بعد أول نجاح (Issue #29 — one-shot)، وإعادة
     * الاختيار أثناء التهيئة تكتب القائمة المعلّقة حصراً (§F.6).
     *
     * T8-15 (F-1): العقد raise-only بالنمط نفسه (`mark` بلا معامل) — لا يستطيع
     * أي مستدعٍ تصفير العلامة في المترجم، فصار الحارس النحوي لا اصطلاحياً.
     */
    suspend fun markTopFiveMaterialized()

    /**
     * يُسجِّل احتفالاً بانتقال أرحام لجهة اتصال (T8-06): يخزّن مفتاح فترة الاحتفال
     * فوق العلامات القائمة. الفحص والقرار في النطاق (`CelebrateTransitionUseCase`) —
     * هذا العقد تخزينٌ حصراً (خطة §D.3-5).
     */
    suspend fun markArhamTransitionCelebrated(contactId: Long, periodKey: Long)
    /** استبعاد بطاقة اقتراح نهائياً (Issue #24 — لا تعود لنفس العناصر). */
    suspend fun dismissSuggestion(id: String)
    /** ضبط أفعال السحب (بداية/نهاية منطقيتان — Issue #24 / Issue #30). */
    suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction)
}

class SilaPreferencesDataSource(
    private val initialPreferences: UserPreferences = UserPreferences(),
    val storageFilePath: String? = null,
    /** مُمرَّر من Koin (AppDispatchers.default) — يملك نطاق التدفق الحار أدناه (نطاق مملوك للنطاق المشترك، بلا نطاق عام عالمي). */
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : UserPreferencesDataSource {

    private val dataStore: DataStore<Preferences>? = storageFilePath?.let { path ->
        getOrCreateDataStore(path)
    }

    private val _memoryState = MutableStateFlow(initialPreferences)

    /**
     * تخزين مؤقت حار لتدفق DataStore القرصي (Issue #34) — مجموعة واحدة مملوكة لكل مصدر بيانات.
     *
     * لماذا حار (Eagerly)؟ تدفق `dataStore.data` بارد، وكل مجموعة جديدة تقرأ الحالة بلا قفل
     * (`readState`) — إن تزامنت قراءتها مع نافذة كتابة ([incrementAndGetVersion ← كتابة القرص ←
     * tryUpdate]) فستحصل على محتوى قديم مختوماً بالإصدار الجديد، فيُسقط حارس `dropWhile` الداخلي
     * تحديث الكتابة **نهائياً** لتلك المجموعة (يظهر عند المستهلك كفقدان الإصدار الثاني وما بعده).
     * مع نطاق مشترك يُعاد جمع التدفق عند كل حقبة اشتراك — لذا فإن الطريق الوحيد لإغلاق السباق
     * هو مجموعة واحدة أبداً لا تُعاد، تُحدَّث قيمتها عبر StateFlow داخلي يوزّع كل تحديث حتمياً.
     *
     * النطاق مملوك لمثيل المصدر ويبقى حياً مدى عمر العملية — نفس النمط الداخلي لمكتبة DataStore
     * نفسها (CoroutineScope(ioDispatcher + SupervisorJob()) بلا إلغاء)، ولا يستخدم النطاق العام
     * العالمي إطلاقاً. البديل في الذاكرة أدناه لا يمسّه هذا التغيير إطلاقاً.
     *
     * دلالة الفشل (مراجعة 4ff0e1b): مع Eagerly لا يوجد جامع يلتقط استثناء مجموعة المشاركة
     * الوحيدة (ملف تالف مثلاً) — بلا معالج لينهار فشل نادر عند الإقلاع حتى بلا مستهلكين؛
     * المعالج هنا يسجل بلا PII ويترك التدفق ميتاً بقيمته الأخيرة — لا كتم لأخطاء الكتابة
     * (تظل تُرمى على المستدعي عبر `edit`) ولا التفاف واسع.
     */
    private val hotDiskPreferences: Flow<UserPreferences>? = dataStore?.let { dataStore ->
        dataStore.data
            .map { prefs -> toUserPreferences(prefs) }
            .shareIn(
                scope = CoroutineScope(
                    SupervisorJob() + defaultDispatcher + CoroutineExceptionHandler { _, throwable ->
                        println("SilaPreferencesDataSource: hot preferences collection failed (no PII): ${throwable::class.simpleName}")
                    }
                ),
                started = SharingStarted.Eagerly,
                replay = 1
            )
    }

    override val preferences: Flow<UserPreferences> = hotDiskPreferences ?: _memoryState.asStateFlow()

    private fun toUserPreferences(prefs: Preferences): UserPreferences = UserPreferences(
        themeMode = prefs[KEY_THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
            ?: initialPreferences.themeMode,
        language = prefs[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: initialPreferences.language,
        digits = prefs[KEY_DIGITS]?.let { runCatching { AppDigits.valueOf(it) }.getOrNull() }
            ?: initialPreferences.digits,
        nameOrder = prefs[KEY_NAME_ORDER]?.let { runCatching { NameOrder.valueOf(it) }.getOrNull() }
            ?: initialPreferences.nameOrder,
        defaultTab = prefs[KEY_DEFAULT_TAB]?.let { runCatching { DefaultTab.valueOf(it) }.getOrNull() }
            ?: initialPreferences.defaultTab,
        visibleAccounts = prefs[KEY_VISIBLE_ACCOUNTS] ?: initialPreferences.visibleAccounts,
        defaultSimSlot = prefs[KEY_DEFAULT_SIM] ?: initialPreferences.defaultSimSlot,
        arhamNotificationsEnabled = prefs[KEY_ARHAM_NOTIFICATIONS] ?: initialPreferences.arhamNotificationsEnabled,
        quietHoursStartHour = prefs[KEY_QUIET_START] ?: initialPreferences.quietHoursStartHour,
        quietHoursEndHour = prefs[KEY_QUIET_END] ?: initialPreferences.quietHoursEndHour,
        arhamNotificationHour = prefs[KEY_ARHAM_NOTIFICATION_HOUR] ?: initialPreferences.arhamNotificationHour,
        arhamNotificationMinute = prefs[KEY_ARHAM_NOTIFICATION_MINUTE] ?: initialPreferences.arhamNotificationMinute,
        arhamMaxPerDay = prefs[KEY_ARHAM_MAX_PER_DAY] ?: initialPreferences.arhamMaxPerDay,
        arhamOccasionDayBeforeEnabled = prefs[KEY_ARHAM_OCCASION_DAY_BEFORE] ?: initialPreferences.arhamOccasionDayBeforeEnabled,
        dashboardPeriod = prefs[KEY_DASHBOARD_PERIOD]?.let { runCatching { DashboardPeriod.valueOf(it) }.getOrNull() }
            ?: initialPreferences.dashboardPeriod,
        confettiEnabled = prefs[KEY_CONFETTI] ?: initialPreferences.confettiEnabled,
        autoTrackingEnabled = prefs[KEY_AUTO_TRACKING] ?: initialPreferences.autoTrackingEnabled,
        defaultCity = prefs[KEY_DEFAULT_CITY] ?: initialPreferences.defaultCity,
        searchRadiusKm = prefs[KEY_SEARCH_RADIUS] ?: initialPreferences.searchRadiusKm,
        quickRatingEnabled = prefs[KEY_QUICK_RATING] ?: initialPreferences.quickRatingEnabled,
        autoUpdatePhonebookServices = prefs[KEY_AUTO_UPDATE_SERVICES] ?: initialPreferences.autoUpdatePhonebookServices,
        smartFilterEnabled = prefs[KEY_SMART_FILTER] ?: initialPreferences.smartFilterEnabled,
        duplicateScanEnabled = prefs[KEY_DUPLICATE_SCAN] ?: initialPreferences.duplicateScanEnabled,
        postCallCategorizeEnabled = prefs[KEY_POST_CALL_CATEGORIZE] ?: initialPreferences.postCallCategorizeEnabled,
        crashReportsEnabled = prefs[KEY_CRASH_REPORTS] ?: initialPreferences.crashReportsEnabled,
        interestTags = prefs[KEY_INTEREST_TAGS] ?: initialPreferences.interestTags,
        categoryRingtones = prefs[KEY_CATEGORY_RINGTONES]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(Category.serializer(), String.serializer()), raw)
            }.getOrNull()
        } ?: initialPreferences.categoryRingtones,
        onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: initialPreferences.onboardingCompleted,
        isGuest = prefs[KEY_IS_GUEST] ?: initialPreferences.isGuest,
        onboardingResumeStep = prefs[KEY_ONBOARDING_RESUME_STEP] ?: initialPreferences.onboardingResumeStep,
        onboardingContactsPermission =
            prefs[KEY_ONBOARDING_CONTACTS_PERMISSION] ?: initialPreferences.onboardingContactsPermission,
        onboardingTopFiveSelections =
            prefs[KEY_ONBOARDING_TOP_FIVE]?.let { raw ->
                runCatching {
                    json.decodeFromString(ListSerializer(TopFiveSelection.serializer()), raw)
                }.getOrNull()
            } ?: initialPreferences.onboardingTopFiveSelections,
        topFiveMaterialized = prefs[KEY_TOP_FIVE_MATERIALIZED] ?: initialPreferences.topFiveMaterialized,
        celebratedArhamTransitions =
            prefs[KEY_CELEBRATED_TRANSITIONS]?.let { raw ->
                runCatching {
                    json.decodeFromString(MapSerializer(Long.serializer(), Long.serializer()), raw)
                }.getOrNull()
            } ?: initialPreferences.celebratedArhamTransitions,
        dismissedSuggestionIds = prefs[KEY_DISMISSED_SUGGESTIONS] ?: initialPreferences.dismissedSuggestionIds,
        swipeStartAction = prefs[KEY_SWIPE_START]?.let { runCatching { ContactSwipeAction.valueOf(it) }.getOrNull() }
            ?: initialPreferences.swipeStartAction,
        swipeEndAction = prefs[KEY_SWIPE_END]?.let { runCatching { ContactSwipeAction.valueOf(it) }.getOrNull() }
            ?: initialPreferences.swipeEndAction
    )

    override suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore?.edit { it[KEY_THEME_MODE] = mode.name }
        _memoryState.update { it.copy(themeMode = mode) }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore?.edit { it[KEY_LANGUAGE] = language.name }
        _memoryState.update { it.copy(language = language) }
    }

    override suspend fun setDigits(digits: AppDigits) {
        dataStore?.edit { it[KEY_DIGITS] = digits.name }
        _memoryState.update { it.copy(digits = digits) }
    }

    override suspend fun setNameOrder(order: NameOrder) {
        dataStore?.edit { it[KEY_NAME_ORDER] = order.name }
        _memoryState.update { it.copy(nameOrder = order) }
    }

    override suspend fun setDefaultTab(tab: DefaultTab) {
        dataStore?.edit { it[KEY_DEFAULT_TAB] = tab.name }
        _memoryState.update { it.copy(defaultTab = tab) }
    }

    override suspend fun setVisibleAccounts(accounts: Set<String>) {
        dataStore?.edit { it[KEY_VISIBLE_ACCOUNTS] = accounts }
        _memoryState.update { it.copy(visibleAccounts = accounts) }
    }

    override suspend fun setDefaultSimSlot(slot: Int?) {
        dataStore?.edit { prefs ->
            if (slot == null) {
                prefs.remove(KEY_DEFAULT_SIM)
            } else {
                prefs[KEY_DEFAULT_SIM] = slot
            }
        }
        _memoryState.update { it.copy(defaultSimSlot = slot) }
    }

    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_ARHAM_NOTIFICATIONS] = enabled }
        _memoryState.update { it.copy(arhamNotificationsEnabled = enabled) }
    }

    override suspend fun setQuietHours(startHour: Int, endHour: Int) {
        val coercedStart = startHour.coerceIn(0, 23)
        val coercedEnd = endHour.coerceIn(0, 23)
        dataStore?.edit {
            it[KEY_QUIET_START] = coercedStart
            it[KEY_QUIET_END] = coercedEnd
        }
        _memoryState.update {
            it.copy(
                quietHoursStartHour = coercedStart,
                quietHoursEndHour = coercedEnd
            )
        }
    }

    /** الوقت المفضل للتذكير اليومي بالتوقيت المحلي (§M «الوقت المفضل» — T8-08). القيم تُضاقَط نطاقها. */
    override suspend fun setArhamNotificationTime(hour: Int, minute: Int) {
        val coercedHour = hour.coerceIn(0, 23)
        val coercedMinute = minute.coerceIn(0, 59)
        dataStore?.edit {
            it[KEY_ARHAM_NOTIFICATION_HOUR] = coercedHour
            it[KEY_ARHAM_NOTIFICATION_MINUTE] = coercedMinute
        }
        _memoryState.update {
            it.copy(
                arhamNotificationHour = coercedHour,
                arhamNotificationMinute = coercedMinute
            )
        }
    }

    override suspend fun setArhamMaxPerDay(max: Int) {
        val coerced = max.coerceAtLeast(1)
        dataStore?.edit { it[KEY_ARHAM_MAX_PER_DAY] = coerced }
        _memoryState.update { it.copy(arhamMaxPerDay = coerced) }
    }

    override suspend fun setArhamOccasionDayBeforeEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_ARHAM_OCCASION_DAY_BEFORE] = enabled }
        _memoryState.update { it.copy(arhamOccasionDayBeforeEnabled = enabled) }
    }

    override suspend fun setDashboardPeriod(period: DashboardPeriod) {
        dataStore?.edit { it[KEY_DASHBOARD_PERIOD] = period.name }
        _memoryState.update { it.copy(dashboardPeriod = period) }
    }

    override suspend fun setConfettiEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_CONFETTI] = enabled }
        _memoryState.update { it.copy(confettiEnabled = enabled) }
    }

    override suspend fun setAutoTrackingEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_AUTO_TRACKING] = enabled }
        _memoryState.update { it.copy(autoTrackingEnabled = enabled) }
    }

    override suspend fun setDefaultCity(city: String?) {
        dataStore?.edit { prefs ->
            if (city.isNullOrBlank()) {
                prefs.remove(KEY_DEFAULT_CITY)
            } else {
                prefs[KEY_DEFAULT_CITY] = city.trim()
            }
        }
        _memoryState.update { it.copy(defaultCity = city?.trim()) }
    }

    override suspend fun setSearchRadiusKm(radiusKm: Int) {
        val coerced = radiusKm.coerceIn(1, 100)
        dataStore?.edit { it[KEY_SEARCH_RADIUS] = coerced }
        _memoryState.update { it.copy(searchRadiusKm = coerced) }
    }

    override suspend fun setQuickRatingEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_QUICK_RATING] = enabled }
        _memoryState.update { it.copy(quickRatingEnabled = enabled) }
    }

    override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) {
        dataStore?.edit { it[KEY_AUTO_UPDATE_SERVICES] = enabled }
        _memoryState.update { it.copy(autoUpdatePhonebookServices = enabled) }
    }

    override suspend fun setSmartFilterEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_SMART_FILTER] = enabled }
        _memoryState.update { it.copy(smartFilterEnabled = enabled) }
    }

    override suspend fun setDuplicateScanEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_DUPLICATE_SCAN] = enabled }
        _memoryState.update { it.copy(duplicateScanEnabled = enabled) }
    }

    override suspend fun setPostCallCategorizeEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_POST_CALL_CATEGORIZE] = enabled }
        _memoryState.update { it.copy(postCallCategorizeEnabled = enabled) }
    }

    override suspend fun setCrashReportsEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_CRASH_REPORTS] = enabled }
        _memoryState.update { it.copy(crashReportsEnabled = enabled) }
    }

    override suspend fun setInterestTags(tags: Set<String>) {
        dataStore?.edit { it[KEY_INTEREST_TAGS] = tags }
        _memoryState.update { it.copy(interestTags = tags) }
    }

    override suspend fun setCategoryRingtone(category: Category, uri: String?) {
        val updated = if (uri.isNullOrBlank()) {
            _memoryState.value.categoryRingtones - category
        } else {
            _memoryState.value.categoryRingtones + (category to uri)
        }
        setCategoryRingtones(updated)
    }

    override suspend fun setCategoryRingtones(ringtones: Map<Category, String>) {
        dataStore?.edit { prefs ->
            val encoded = runCatching {
                json.encodeToString(MapSerializer(Category.serializer(), String.serializer()), ringtones)
            }
            encoded.onSuccess { prefs[KEY_CATEGORY_RINGTONES] = it }
                .onFailure { prefs.remove(KEY_CATEGORY_RINGTONES) }
        }
        _memoryState.update { it.copy(categoryRingtones = ringtones) }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore?.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
        _memoryState.update { it.copy(onboardingCompleted = completed) }
    }

    override suspend fun setIsGuest(isGuest: Boolean) {
        dataStore?.edit { it[KEY_IS_GUEST] = isGuest }
        _memoryState.update { it.copy(isGuest = isGuest) }
    }

    override suspend fun setOnboardingResumeStep(step: String?) {
        dataStore?.edit { prefs ->
            if (step == null) {
                prefs.remove(KEY_ONBOARDING_RESUME_STEP)
            } else {
                prefs[KEY_ONBOARDING_RESUME_STEP] = step
            }
        }
        _memoryState.update { it.copy(onboardingResumeStep = step) }
    }

    override suspend fun setOnboardingContactsPermission(permission: String?) {
        dataStore?.edit { prefs ->
            if (permission == null) {
                prefs.remove(KEY_ONBOARDING_CONTACTS_PERMISSION)
            } else {
                prefs[KEY_ONBOARDING_CONTACTS_PERMISSION] = permission
            }
        }
        _memoryState.update { it.copy(onboardingContactsPermission = permission) }
    }

    override suspend fun setOnboardingTopFiveSelections(selections: List<TopFiveSelection>) {
        dataStore?.edit { prefs ->
            val encoded = runCatching { json.encodeToString(ListSerializer(TopFiveSelection.serializer()), selections) }
            encoded.onSuccess { prefs[KEY_ONBOARDING_TOP_FIVE] = it }
                .onFailure { prefs.remove(KEY_ONBOARDING_TOP_FIVE) }
        }
        _memoryState.update { it.copy(onboardingTopFiveSelections = selections) }
    }

    override suspend fun markTopFiveMaterialized() {
        dataStore?.edit { it[KEY_TOP_FIVE_MATERIALIZED] = true }
        _memoryState.update { it.copy(topFiveMaterialized = true) }
    }

    override suspend fun markArhamTransitionCelebrated(contactId: Long, periodKey: Long) {
        if (dataStore == null) {
            _memoryState.update {
                it.copy(celebratedArhamTransitions = it.celebratedArhamTransitions + (contactId to periodKey))
            }
            return
        }
        var merged = _memoryState.value.celebratedArhamTransitions
        dataStore.edit { prefs ->
            val persisted = prefs[KEY_CELEBRATED_TRANSITIONS]?.let { raw ->
                runCatching {
                    json.decodeFromString(MapSerializer(Long.serializer(), Long.serializer()), raw)
                }.getOrNull()
            } ?: _memoryState.value.celebratedArhamTransitions
            val updated = persisted + (contactId to periodKey)
            val encoded =
                runCatching { json.encodeToString(MapSerializer(Long.serializer(), Long.serializer()), updated) }
            encoded.onSuccess { prefs[KEY_CELEBRATED_TRANSITIONS] = it }
                .onFailure { prefs.remove(KEY_CELEBRATED_TRANSITIONS) }
            merged = updated
        }
        _memoryState.update { it.copy(celebratedArhamTransitions = merged) }
    }

    override suspend fun dismissSuggestion(id: String) {
        dataStore?.edit { prefs ->
            prefs[KEY_DISMISSED_SUGGESTIONS] = (prefs[KEY_DISMISSED_SUGGESTIONS] ?: emptySet()) + id
        }
        _memoryState.update { it.copy(dismissedSuggestionIds = it.dismissedSuggestionIds + id) }
    }

    override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) {
        dataStore?.edit {
            it[KEY_SWIPE_START] = start.name
            it[KEY_SWIPE_END] = end.name
        }
        _memoryState.update { it.copy(swipeStartAction = start, swipeEndAction = end) }
    }

    companion object {
        const val DATASTORE_FILE_NAME = "sila_user_preferences.preferences_pb"

        private val dataStoreRegistry = mutableMapOf<String, DataStore<Preferences>>()

        private fun getOrCreateDataStore(path: String): DataStore<Preferences> {
            val normalizedPath = path.toPath().toString()
            return dataStoreRegistry.getOrPut(normalizedPath) {
                PreferenceDataStoreFactory.createWithPath(
                    produceFile = { path.toPath() }
                )
            }
        }

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_DIGITS = stringPreferencesKey("app_digits")
        private val KEY_NAME_ORDER = stringPreferencesKey("name_order")
        private val KEY_DEFAULT_TAB = stringPreferencesKey("default_tab")
        private val KEY_VISIBLE_ACCOUNTS = stringSetPreferencesKey("visible_accounts")
        private val KEY_DEFAULT_SIM = intPreferencesKey("default_sim_slot")
        private val KEY_ARHAM_NOTIFICATIONS = booleanPreferencesKey("arham_notifications_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
        private val KEY_ARHAM_NOTIFICATION_HOUR = intPreferencesKey("arham_notification_hour")
        private val KEY_ARHAM_NOTIFICATION_MINUTE = intPreferencesKey("arham_notification_minute")
        private val KEY_ARHAM_MAX_PER_DAY = intPreferencesKey("arham_max_per_day")
        private val KEY_ARHAM_OCCASION_DAY_BEFORE = booleanPreferencesKey("arham_occasion_day_before")
        private val KEY_DASHBOARD_PERIOD = stringPreferencesKey("dashboard_period")
        private val KEY_CONFETTI = booleanPreferencesKey("confetti_enabled")
        private val KEY_AUTO_TRACKING = booleanPreferencesKey("auto_tracking")
        private val KEY_DEFAULT_CITY = stringPreferencesKey("default_city")
        private val KEY_SEARCH_RADIUS = intPreferencesKey("search_radius_km")
        private val KEY_QUICK_RATING = booleanPreferencesKey("quick_rating")
        private val KEY_AUTO_UPDATE_SERVICES = booleanPreferencesKey("auto_update_phonebook_services")
        private val KEY_SMART_FILTER = booleanPreferencesKey("smart_filter_enabled")
        private val KEY_DUPLICATE_SCAN = booleanPreferencesKey("duplicate_scan")
        private val KEY_POST_CALL_CATEGORIZE = booleanPreferencesKey("post_call_categorize")
        private val KEY_CRASH_REPORTS = booleanPreferencesKey("crash_reports")
        private val KEY_INTEREST_TAGS = stringSetPreferencesKey("interest_tags")
        private val KEY_CATEGORY_RINGTONES = stringPreferencesKey("category_ringtones")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_IS_GUEST = booleanPreferencesKey("is_guest")
        private val KEY_ONBOARDING_RESUME_STEP = stringPreferencesKey("onboarding_resume_step")
        private val KEY_ONBOARDING_CONTACTS_PERMISSION = stringPreferencesKey("onboarding_contacts_permission")
        private val KEY_ONBOARDING_TOP_FIVE = stringPreferencesKey("onboarding_top_five_selections")
        private val KEY_TOP_FIVE_MATERIALIZED = booleanPreferencesKey("top_five_materialized")
        private val KEY_CELEBRATED_TRANSITIONS = stringPreferencesKey("arham_celebrated_transitions")
        private val KEY_DISMISSED_SUGGESTIONS = stringSetPreferencesKey("dismissed_suggestion_ids")
        private val KEY_SWIPE_START = stringPreferencesKey("swipe_start_action")
        private val KEY_SWIPE_END = stringPreferencesKey("swipe_end_action")

        private val json = Json { ignoreUnknownKeys = true }
    }
}
