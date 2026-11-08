package app.sila.feature.settings.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupArhamEntry
import app.sila.feature.settings.domain.BackupCategoryEntry
import app.sila.feature.settings.domain.BackupPayload
import app.sila.feature.settings.domain.BackupPreferencesEntry
import app.sila.feature.settings.domain.BackupTagEntry
import app.sila.feature.settings.domain.RestoreMode
import app.sila.feature.settings.domain.RestoreResult
import app.sila.feature.settings.domain.SettingsRepository
import app.sila.feature.settings.domain.UnmatchedBackupEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * تنفيذ مستودع الإعدادات (طبقة Data داخل :feature:settings:data).
 * يتعامل مع مصدر التفضيلات (DataStore) ومصادر قاعدة البيانات (Room DAOs) محلياً.
 */
class SettingsRepositoryImpl(
    private val dataSource: UserPreferencesDataSource,
    private val contactDao: ContactDao? = null,
    private val arhamDao: ArhamDao? = null
) : SettingsRepository {

    override val preferences: Flow<UserPreferences> = dataSource.preferences

    override suspend fun setThemeMode(mode: AppThemeMode) = dataSource.setThemeMode(mode)
    override suspend fun setLanguage(language: AppLanguage) = dataSource.setLanguage(language)
    override suspend fun setDigits(digits: AppDigits) = dataSource.setDigits(digits)
    override suspend fun setNameOrder(order: NameOrder) = dataSource.setNameOrder(order)
    override suspend fun setDefaultTab(tab: DefaultTab) = dataSource.setDefaultTab(tab)
    override suspend fun setVisibleAccounts(accounts: Set<String>) = dataSource.setVisibleAccounts(accounts)
    override suspend fun setDefaultSimSlot(slot: Int?) = dataSource.setDefaultSimSlot(slot)
    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) = dataSource.setArhamNotificationsEnabled(enabled)
    override suspend fun setArhamNotificationTime(hour: Int, minute: Int) = dataSource.setArhamNotificationTime(hour, minute)
    override suspend fun setArhamMaxPerDay(max: Int) = dataSource.setArhamMaxPerDay(max)
    override suspend fun setQuietHours(startHour: Int, endHour: Int) = dataSource.setQuietHours(startHour, endHour)
    override suspend fun setDashboardPeriod(period: DashboardPeriod) = dataSource.setDashboardPeriod(period)
    override suspend fun setConfettiEnabled(enabled: Boolean) = dataSource.setConfettiEnabled(enabled)
    override suspend fun setAutoTrackingEnabled(enabled: Boolean) = dataSource.setAutoTrackingEnabled(enabled)
    override suspend fun setDefaultCity(city: String?) = dataSource.setDefaultCity(city)
    override suspend fun setSearchRadiusKm(radiusKm: Int) = dataSource.setSearchRadiusKm(radiusKm)
    override suspend fun setQuickRatingEnabled(enabled: Boolean) = dataSource.setQuickRatingEnabled(enabled)
    override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) = dataSource.setAutoUpdatePhonebookServices(enabled)
    override suspend fun setSmartFilterEnabled(enabled: Boolean) = dataSource.setSmartFilterEnabled(enabled)
    override suspend fun setDuplicateScanEnabled(enabled: Boolean) = dataSource.setDuplicateScanEnabled(enabled)
    override suspend fun setPostCallCategorizeEnabled(enabled: Boolean) = dataSource.setPostCallCategorizeEnabled(enabled)
    override suspend fun setCrashReportsEnabled(enabled: Boolean) = dataSource.setCrashReportsEnabled(enabled)
    override suspend fun setInterestTags(tags: Set<String>) = dataSource.setInterestTags(tags)
    override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) = dataSource.setSwipeActions(start, end)
    override suspend fun setCategoryRingtone(category: Category, uri: String?) = dataSource.setCategoryRingtone(category, uri)
    override suspend fun setCategoryRingtones(ringtones: Map<Category, String>) = dataSource.setCategoryRingtones(ringtones)

    override suspend fun getBackupData(): BackupPayload {
        val allContacts = contactDao?.observeContacts()?.first() ?: emptyList()

        val categoryEntries = mutableListOf<BackupCategoryEntry>()
        val arhamEntries = mutableListOf<BackupArhamEntry>()
        val tagEntries = mutableListOf<BackupTagEntry>()

        for (contact in allContacts) {
            val key = contact.lookupKey ?: contact.systemContactId
            if (contact.category != Category.UNCATEGORIZED) {
                categoryEntries += BackupCategoryEntry(
                    lookupKey = key,
                    displayName = contact.name.displayName,
                    category = contact.category
                )
            }
            contact.arham?.let { arham ->
                arhamEntries += BackupArhamEntry(
                    lookupKey = key,
                    relation = arham.relation,
                    frequency = arham.frequency
                )
            }
            if (contact.tags.isNotEmpty()) {
                tagEntries += BackupTagEntry(
                    lookupKey = key,
                    tags = contact.tags
                )
            }
        }

        val currentPrefs = dataSource.preferences.first()
        val prefsEntry = BackupPreferencesEntry(
            themeMode = currentPrefs.themeMode,
            language = currentPrefs.language,
            digits = currentPrefs.digits,
            nameOrder = currentPrefs.nameOrder,
            defaultTab = currentPrefs.defaultTab,
            visibleAccounts = currentPrefs.visibleAccounts,
            defaultSimSlot = currentPrefs.defaultSimSlot,
            arhamNotificationsEnabled = currentPrefs.arhamNotificationsEnabled,
            quietHoursStartHour = currentPrefs.quietHoursStartHour,
            quietHoursEndHour = currentPrefs.quietHoursEndHour,
            arhamNotificationHour = currentPrefs.arhamNotificationHour,
            arhamNotificationMinute = currentPrefs.arhamNotificationMinute,
            arhamMaxPerDay = currentPrefs.arhamMaxPerDay,
            arhamOccasionDayBeforeEnabled = currentPrefs.arhamOccasionDayBeforeEnabled,
            dashboardPeriod = currentPrefs.dashboardPeriod,
            confettiEnabled = currentPrefs.confettiEnabled,
            autoTrackingEnabled = currentPrefs.autoTrackingEnabled,
            defaultCity = currentPrefs.defaultCity,
            searchRadiusKm = currentPrefs.searchRadiusKm,
            quickRatingEnabled = currentPrefs.quickRatingEnabled,
            autoUpdatePhonebookServices = currentPrefs.autoUpdatePhonebookServices,
            smartFilterEnabled = currentPrefs.smartFilterEnabled,
            duplicateScanEnabled = currentPrefs.duplicateScanEnabled,
            postCallCategorizeEnabled = currentPrefs.postCallCategorizeEnabled,
            crashReportsEnabled = currentPrefs.crashReportsEnabled,
            interestTags = currentPrefs.interestTags,
            swipeStartAction = currentPrefs.swipeStartAction,
            swipeEndAction = currentPrefs.swipeEndAction
        )

        return BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = categoryEntries,
            arham = arhamEntries,
            tags = tagEntries,
            preferences = prefsEntry,
            categoryRingtones = currentPrefs.categoryRingtones,
            automations = emptyList(),
            disputes = emptyList()
        )
    }

    override suspend fun restoreBackupData(
        payload: BackupPayload,
        mode: RestoreMode,
        restorePreferences: Boolean
    ): RestoreResult {
        val allContacts = contactDao?.observeContacts()?.first() ?: emptyList()
        val contactByLookup = mutableMapOf<String, Long>()
        for (c in allContacts) {
            c.lookupKey?.let { contactByLookup[it] = c.id }
            contactByLookup[c.systemContactId] = c.id
        }

        var matchedCategoriesCount = 0
        val unmatchedCategories = mutableListOf<BackupCategoryEntry>()

        if (mode == RestoreMode.REPLACE) {
            // في وضع الاستبدال، يُعاد تعيين تصنيفات جهات الاتصال الحالية
            for (c in allContacts) {
                contactDao?.updateCategory(c.id, Category.UNCATEGORIZED)
            }
        }

        for (cat in payload.categories) {
            val contactId = contactByLookup[cat.lookupKey]
            if (contactId != null) {
                contactDao?.updateCategory(contactId, cat.category)
                matchedCategoriesCount++
            } else {
                unmatchedCategories += cat
            }
        }

        var matchedArhamCount = 0
        var unmatchedArhamCount = 0

        for (arham in payload.arham) {
            val contactId = contactByLookup[arham.lookupKey]
            if (contactId != null) {
                arhamDao?.upsertArhamInfo(
                    contactId = contactId,
                    info = ArhamInfo(
                        relation = arham.relation,
                        frequency = arham.frequency
                    )
                )
                matchedArhamCount++
            } else {
                unmatchedArhamCount++
            }
        }

        // استعادة التفضيلات إذا طلب المستخدم صراحة ذلك (AC: لا تُستعاد التفضيلات تلقائياً)
        if (restorePreferences) {
            payload.preferences?.let { p ->
                dataSource.setThemeMode(p.themeMode)
                dataSource.setLanguage(p.language)
                dataSource.setDigits(p.digits)
                dataSource.setNameOrder(p.nameOrder)
                dataSource.setDefaultTab(p.defaultTab)
                dataSource.setVisibleAccounts(p.visibleAccounts)
                dataSource.setDefaultSimSlot(p.defaultSimSlot)
                dataSource.setArhamNotificationsEnabled(p.arhamNotificationsEnabled)
                dataSource.setQuietHours(p.quietHoursStartHour, p.quietHoursEndHour)
                dataSource.setArhamNotificationTime(p.arhamNotificationHour, p.arhamNotificationMinute)
                dataSource.setArhamMaxPerDay(p.arhamMaxPerDay)
                dataSource.setDashboardPeriod(p.dashboardPeriod)
                dataSource.setConfettiEnabled(p.confettiEnabled)
                dataSource.setAutoTrackingEnabled(p.autoTrackingEnabled)
                dataSource.setDefaultCity(p.defaultCity)
                dataSource.setSearchRadiusKm(p.searchRadiusKm)
                dataSource.setQuickRatingEnabled(p.quickRatingEnabled)
                dataSource.setAutoUpdatePhonebookServices(p.autoUpdatePhonebookServices)
                dataSource.setSmartFilterEnabled(p.smartFilterEnabled)
                dataSource.setDuplicateScanEnabled(p.duplicateScanEnabled)
                dataSource.setPostCallCategorizeEnabled(p.postCallCategorizeEnabled)
                dataSource.setCrashReportsEnabled(p.crashReportsEnabled)
                dataSource.setInterestTags(p.interestTags)
                dataSource.setSwipeActions(p.swipeStartAction, p.swipeEndAction)
            }
            dataSource.setCategoryRingtones(payload.categoryRingtones)
        }

        val arhamMap = payload.arham.associateBy { it.lookupKey }
        val unmatchedList = unmatchedCategories.map { un ->
            UnmatchedBackupEntry(
                lookupKey = un.lookupKey,
                displayName = un.displayName,
                category = un.category,
                arhamRelation = arhamMap[un.lookupKey]?.relation
            )
        }

        return RestoreResult(
            matchedCategories = matchedCategoriesCount,
            unmatchedCategories = unmatchedCategories.size,
            matchedArham = matchedArhamCount,
            unmatchedArham = unmatchedArhamCount,
            restoredPreferences = restorePreferences,
            unmatchedEntries = unmatchedList
        )
    }
}
