package app.sila.feature.settings.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.LastContactedUpdate
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallEvent
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.RecentInteraction
import app.sila.core.model.Relation
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupArhamEntry
import app.sila.feature.settings.domain.BackupCategoryEntry
import app.sila.feature.settings.domain.BackupPayload
import app.sila.feature.settings.domain.BackupPreferencesEntry
import app.sila.feature.settings.domain.BackupTagEntry
import app.sila.feature.settings.domain.RestoreMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryImplTest {

    private class FakeContactDao(
        initialContacts: List<Contact> = emptyList()
    ) : ContactDao {
        val contactsMap = initialContacts.associateBy { it.id }.toMutableMap()
        private val _flow = MutableStateFlow(contactsMap.values.toList())

        private fun syncFlow() {
            _flow.value = contactsMap.values.toList()
        }

        override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> =
            _flow.asStateFlow()

        override fun observeFavorites(): Flow<List<Contact>> = _flow.asStateFlow()
        override fun observeCategoryCounts(): Flow<Map<Category, Int>> =
            MutableStateFlow(emptyMap())

        override suspend fun getContactById(id: Long): Contact? = contactsMap[id]
        override fun observeContactById(id: Long): Flow<Contact?> =
            MutableStateFlow(contactsMap[id])

        override suspend fun upsertContact(contact: Contact) {
            contactsMap[contact.id] = contact
            syncFlow()
        }

        override suspend fun updateCategory(contactId: Long, category: Category) {
            contactsMap[contactId]?.let {
                contactsMap[contactId] = it.copy(category = category)
                syncFlow()
            }
        }

        override suspend fun deleteContact(contactId: Long) {
            contactsMap.remove(contactId)
            syncFlow()
        }

        override fun searchContacts(query: String): Flow<List<Contact>> = _flow.asStateFlow()
        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {}
        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {}
        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {}
        override suspend fun deleteContacts(contactIds: Collection<Long>) {}
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) {}
        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction> = emptyList()
        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> = emptyList()
        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) {}
    }

    private class FakeArhamDao : ArhamDao {
        val arhamMap = mutableMapOf<Long, ArhamInfo>()

        override fun observeArhamContacts(): Flow<List<Contact>> = MutableStateFlow(emptyList())

        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
            arhamMap[contactId] = info
        }

        override suspend fun deleteArhamInfo(contactId: Long) {
            arhamMap.remove(contactId)
        }

        override fun observePendingCallEvents(): Flow<List<CallEvent>> = MutableStateFlow(emptyList())
        override fun observeCallEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> = MutableStateFlow(emptyList())
        override suspend fun getCallEventById(eventId: String): CallEvent? = null
        override suspend fun findCallEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> = emptyList()
        override suspend fun upsertCallEvent(event: CallEvent, lastContactedUpdate: LastContactedUpdate?) {}
        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {}
    }

    private fun createContact(
        id: Long,
        lookupKey: String,
        displayName: String,
        category: Category = Category.UNCATEGORIZED,
        tags: List<String> = emptyList(),
        arham: ArhamInfo? = null
    ): Contact = Contact(
        id = id,
        systemContactId = lookupKey,
        lookupKey = lookupKey,
        name = ContactName(displayName = displayName),
        category = category,
        tags = tags,
        arham = arham
    )

    @Test
    fun getBackupData_collectsCategoriesArhamTagsAndPreferences() = runTest {
        val contact1 = createContact(
            id = 1,
            lookupKey = "lookup_1",
            displayName = "أحمد محمد",
            category = Category.FAMILY,
            tags = listOf("طبيب", "أخي"),
            arham = ArhamInfo(relation = Relation.BROTHER, frequency = ArhamFrequency.WEEKLY)
        )
        val contact2 = createContact(
            id = 2,
            lookupKey = "lookup_2",
            displayName = "علي خالد",
            category = Category.WORK,
            tags = listOf("زميل")
        )

        val fakeContactDao = FakeContactDao(listOf(contact1, contact2))
        val fakeArhamDao = FakeArhamDao()
        val dataSource = SilaPreferencesDataSource()
        dataSource.setThemeMode(AppThemeMode.DARK)

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )

        val payload = repository.getBackupData()

        assertEquals(1, payload.version)
        assertEquals(2, payload.categories.size)
        assertEquals(1, payload.arham.size)
        assertEquals(2, payload.tags.size)
        assertEquals(AppThemeMode.DARK, payload.preferences?.themeMode)
    }

    @Test
    fun restoreBackupData_mergeMode_updatesMatchedAndPreservesRest() = runTest {
        val existing1 = createContact(id = 1, lookupKey = "lookup_1", displayName = "أحمد", category = Category.UNCATEGORIZED)
        val existing2 = createContact(id = 2, lookupKey = "lookup_2", displayName = "سارة", category = Category.WORK)

        val fakeContactDao = FakeContactDao(listOf(existing1, existing2))
        val fakeArhamDao = FakeArhamDao()
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )

        val payload = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = listOf(
                BackupCategoryEntry(lookupKey = "lookup_1", displayName = "أحمد", category = Category.FAMILY),
                BackupCategoryEntry(lookupKey = "lookup_unknown", displayName = "مجهول", category = Category.FRIENDS)
            ),
            arham = listOf(
                BackupArhamEntry(lookupKey = "lookup_1", relation = Relation.BROTHER, frequency = ArhamFrequency.WEEKLY),
                BackupArhamEntry(lookupKey = "lookup_arham_unknown", relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.MONTHLY)
            ),
            tags = listOf(
                BackupTagEntry(lookupKey = "lookup_1", tags = listOf("عائلة", "طبيب"))
            ),
            preferences = null,
            categoryRingtones = emptyMap(),
            automations = emptyList(),
            disputes = emptyList()
        )

        val result = repository.restoreBackupData(payload, mode = RestoreMode.MERGE, restorePreferences = false)

        assertEquals(1, result.matchedCategories)
        assertEquals(1, result.unmatchedCategories)
        assertEquals(1, result.matchedArham)
        assertEquals(1, result.unmatchedArham)
        assertFalse(result.restoredPreferences)
        assertEquals(2, result.unmatchedEntries.size)

        // التحقق من تحديث جهة الاتصال 1
        val updated1 = fakeContactDao.getContactById(1)
        assertEquals(Category.FAMILY, updated1?.category)
        assertEquals(listOf("عائلة", "طبيب"), updated1?.tags)
        assertEquals(Relation.BROTHER, fakeArhamDao.arhamMap[1]?.relation)

        // التحقق من بقاء جهة الاتصال 2 دون تغيير في وضع الدمج
        val unchanged2 = fakeContactDao.getContactById(2)
        assertEquals(Category.WORK, unchanged2?.category)
    }

    @Test
    fun restoreBackupData_replaceMode_resetsPreviousCategoriesAndTags() = runTest {
        val existing1 = createContact(id = 1, lookupKey = "lookup_1", displayName = "أحمد", category = Category.WORK, tags = listOf("قديم"))
        val existing2 = createContact(id = 2, lookupKey = "lookup_2", displayName = "سارة", category = Category.FRIENDS, tags = listOf("جامعة"))

        val fakeContactDao = FakeContactDao(listOf(existing1, existing2))
        val fakeArhamDao = FakeArhamDao()
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )

        val payload = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = listOf(
                BackupCategoryEntry(lookupKey = "lookup_1", displayName = "أحمد", category = Category.FAMILY)
            ),
            arham = emptyList(),
            tags = emptyList(),
            preferences = null,
            categoryRingtones = emptyMap(),
            automations = emptyList(),
            disputes = emptyList()
        )

        repository.restoreBackupData(payload, mode = RestoreMode.REPLACE, restorePreferences = false)

        // جهة 1 تأخذ التصنيف الجديد من النسخة
        assertEquals(Category.FAMILY, fakeContactDao.getContactById(1)?.category)
        // جهة 2 لم تكن في النسخة فيتم تصفير تصنيفها ووسومها
        assertEquals(Category.UNCATEGORIZED, fakeContactDao.getContactById(2)?.category)
        assertTrue(fakeContactDao.getContactById(2)?.tags?.isEmpty() == true)
    }

    @Test
    fun restoreBackupData_withRestorePreferencesTrue_restoresSettings() = runTest {
        val fakeContactDao = FakeContactDao()
        val fakeArhamDao = FakeArhamDao()
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )

        val prefsEntry = BackupPreferencesEntry(
            themeMode = AppThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            digits = AppDigits.HINDI,
            nameOrder = NameOrder.LAST_FIRST,
            defaultTab = DefaultTab.ARHAM,
            visibleAccounts = setOf("Google"),
            defaultSimSlot = 2,
            arhamNotificationsEnabled = true,
            quietHoursStartHour = 21,
            quietHoursEndHour = 7,
            arhamNotificationHour = 18,
            arhamNotificationMinute = 30,
            arhamMaxPerDay = 3,
            arhamOccasionDayBeforeEnabled = true,
            dashboardPeriod = app.sila.core.model.DashboardPeriod.MONTH,
            confettiEnabled = false,
            autoTrackingEnabled = false,
            defaultCity = "Cairo",
            searchRadiusKm = 10,
            quickRatingEnabled = false,
            autoUpdatePhonebookServices = true,
            smartFilterEnabled = false,
            duplicateScanEnabled = false,
            postCallCategorizeEnabled = false,
            crashReportsEnabled = true,
            interestTags = setOf("تقنية"),
            swipeStartAction = app.sila.core.model.ContactSwipeAction.MESSAGE,
            swipeEndAction = app.sila.core.model.ContactSwipeAction.CALL
        )

        val payload = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = emptyList(),
            arham = emptyList(),
            tags = emptyList(),
            preferences = prefsEntry,
            categoryRingtones = mapOf(Category.FAMILY to "content://media/chime"),
            automations = emptyList(),
            disputes = emptyList()
        )

        val result = repository.restoreBackupData(payload, mode = RestoreMode.MERGE, restorePreferences = true)

        assertTrue(result.restoredPreferences)
        val restoredPrefs = dataSource.preferences.first()
        assertEquals(AppThemeMode.DARK, restoredPrefs.themeMode)
        assertEquals(AppLanguage.ENGLISH, restoredPrefs.language)
        assertEquals(AppDigits.HINDI, restoredPrefs.digits)
        assertEquals(NameOrder.LAST_FIRST, restoredPrefs.nameOrder)
        assertEquals(2, restoredPrefs.defaultSimSlot)
        assertEquals(21, restoredPrefs.quietHoursStartHour)
        assertEquals("content://media/chime", restoredPrefs.categoryRingtones[Category.FAMILY])
    }
}
