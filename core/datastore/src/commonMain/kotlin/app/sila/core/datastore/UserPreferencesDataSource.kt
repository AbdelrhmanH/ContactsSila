package app.sila.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.DefaultTab
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    suspend fun setDefaultTab(tab: DefaultTab)
    suspend fun setArhamNotificationsEnabled(enabled: Boolean)
    suspend fun setQuietHours(startHour: Int, endHour: Int)
    suspend fun setConfettiEnabled(enabled: Boolean)
    suspend fun setSearchRadiusKm(radiusKm: Int)
    suspend fun setAutoUpdatePhonebookServices(enabled: Boolean)
    suspend fun setSmartFilterEnabled(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
}

class SilaPreferencesDataSource(
    initialPreferences: UserPreferences = UserPreferences(),
    val storageFilePath: String? = null
) : UserPreferencesDataSource {

    private val dataStore: DataStore<Preferences>? = storageFilePath?.let { path ->
        getOrCreateDataStore(path)
    }

    private val _memoryState = MutableStateFlow(initialPreferences)

    override val preferences: Flow<UserPreferences> = dataStore?.data?.map { prefs ->
        UserPreferences(
            themeMode = prefs[KEY_THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: initialPreferences.themeMode,
            language = prefs[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: initialPreferences.language,
            defaultTab = prefs[KEY_DEFAULT_TAB]?.let { runCatching { DefaultTab.valueOf(it) }.getOrNull() }
                ?: initialPreferences.defaultTab,
            arhamNotificationsEnabled = prefs[KEY_ARHAM_NOTIFICATIONS] ?: initialPreferences.arhamNotificationsEnabled,
            quietHoursStartHour = prefs[KEY_QUIET_START] ?: initialPreferences.quietHoursStartHour,
            quietHoursEndHour = prefs[KEY_QUIET_END] ?: initialPreferences.quietHoursEndHour,
            confettiEnabled = prefs[KEY_CONFETTI] ?: initialPreferences.confettiEnabled,
            searchRadiusKm = prefs[KEY_SEARCH_RADIUS] ?: initialPreferences.searchRadiusKm,
            autoUpdatePhonebookServices = prefs[KEY_AUTO_UPDATE_SERVICES] ?: initialPreferences.autoUpdatePhonebookServices,
            smartFilterEnabled = prefs[KEY_SMART_FILTER] ?: initialPreferences.smartFilterEnabled,
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: initialPreferences.onboardingCompleted
        )
    } ?: _memoryState.asStateFlow()

    override suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore?.edit { it[KEY_THEME_MODE] = mode.name }
        _memoryState.update { it.copy(themeMode = mode) }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore?.edit { it[KEY_LANGUAGE] = language.name }
        _memoryState.update { it.copy(language = language) }
    }

    override suspend fun setDefaultTab(tab: DefaultTab) {
        dataStore?.edit { it[KEY_DEFAULT_TAB] = tab.name }
        _memoryState.update { it.copy(defaultTab = tab) }
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

    override suspend fun setConfettiEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_CONFETTI] = enabled }
        _memoryState.update { it.copy(confettiEnabled = enabled) }
    }

    override suspend fun setSearchRadiusKm(radiusKm: Int) {
        val coerced = radiusKm.coerceIn(1, 100)
        dataStore?.edit { it[KEY_SEARCH_RADIUS] = coerced }
        _memoryState.update { it.copy(searchRadiusKm = coerced) }
    }

    override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) {
        dataStore?.edit { it[KEY_AUTO_UPDATE_SERVICES] = enabled }
        _memoryState.update { it.copy(autoUpdatePhonebookServices = enabled) }
    }

    override suspend fun setSmartFilterEnabled(enabled: Boolean) {
        dataStore?.edit { it[KEY_SMART_FILTER] = enabled }
        _memoryState.update { it.copy(smartFilterEnabled = enabled) }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore?.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
        _memoryState.update { it.copy(onboardingCompleted = completed) }
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
        private val KEY_DEFAULT_TAB = stringPreferencesKey("default_tab")
        private val KEY_ARHAM_NOTIFICATIONS = booleanPreferencesKey("arham_notifications_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
        private val KEY_CONFETTI = booleanPreferencesKey("confetti_enabled")
        private val KEY_SEARCH_RADIUS = intPreferencesKey("search_radius_km")
        private val KEY_AUTO_UPDATE_SERVICES = booleanPreferencesKey("auto_update_phonebook_services")
        private val KEY_SMART_FILTER = booleanPreferencesKey("smart_filter_enabled")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
