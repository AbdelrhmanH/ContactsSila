package app.sila.feature.settings.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ClassificationRestoreOutcome
import app.sila.core.database.dao.ClassificationRestorePlan
import app.sila.core.database.dao.ClassificationRestoreRunner
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.DisputeLogEntityDao
import app.sila.core.database.dao.LastContactedUpdate
import app.sila.core.database.entity.DisputeLogEntity
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
import app.sila.core.model.DisputeStatus
import app.sila.core.model.ImportantDate
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
import app.sila.feature.settings.domain.UnmatchedBackupEntry
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

        // محاكاة بحث DAO الحقيقي (FTS + أرقام): تصفية بالاسم على ما يعادله من المجموعة
        override fun searchContacts(query: String): Flow<List<Contact>> = MutableStateFlow(
            if (query.isBlank()) emptyList()
            else contactsMap.values.filter { it.name.displayName.contains(query) || it.systemContactId.contains(query) }
        )
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

    /**
     * DAO نزاعات زائف (تذكرة #61 — S1-5): يخزن الصفوف بمخزن حي ويعيدها عبر النية
     * القارئة — لاختبار أن التصدير يقرأ Room حصراً بدل القائمة المدوّنة الفارغة.
     */
    private class FakeDisputeLogEntityDao(
        initialRows: List<DisputeLogEntity> = emptyList()
    ) : DisputeLogEntityDao {
        val rows = initialRows.toMutableList()
        private val flow = MutableStateFlow(rows.toList())

        private fun syncFlow() {
            flow.value = rows.toList()
        }

        override suspend fun insert(dispute: DisputeLogEntity): Long {
            val nextId = (rows.maxOfOrNull { it.id } ?: 0L) + 1
            rows += dispute.copy(id = nextId)
            syncFlow()
            return nextId
        }

        // دلالة upsert البسيط للاختبار: الاستبدال بالمعرّف ثم الحفظ بترتيب المعرفات.
        override suspend fun upsertAll(disputes: List<DisputeLogEntity>) {
            val byId = rows.associateBy({ it.id }, { it }).toMutableMap()
            for (row in disputes) byId[row.id] = row
            rows.clear()
            rows += byId.toSortedMap().values
            syncFlow()
        }

        override suspend fun countResolvedOlderThan(cutoff: Long): Int =
            rows.count { it.status == DisputeStatus.RESOLVED && (it.resolvedAt ?: Long.MAX_VALUE) < cutoff }

        override suspend fun archiveResolvedOlderThan(cutoff: Long, nowMillis: Long): Int {
            var archived = 0
            for (index in rows.indices) {
                val row = rows[index]
                if (row.status == DisputeStatus.RESOLVED && (row.resolvedAt ?: Long.MAX_VALUE) < cutoff) {
                    rows[index] = row.copy(status = DisputeStatus.ARCHIVED, updatedAt = nowMillis)
                    archived++
                }
            }
            syncFlow()
            return archived
        }

        override fun observeDisputeLogsOrdered(): Flow<List<DisputeLogEntity>> = flow.asStateFlow()
    }

    /**
     * ممرّ الاستعادة الزائف (تذكرة #60 · ADR-0015): يطبّق الخطة بكامل ترتيبها كما
     * يفعل المعامل الحقيقي — شطف REPLACE أولاً ثم النسخ الموحّدة بتحقق وجود
     * داخل المعاملة، ويعيد الناتج درجياً. [deleteBeforeRewrites] يحاكي غياباً/قبراً
     * يُكتشف داخل المعاملة ليُثبت أن احتساب غير-المطابقة يأتي من ناتج المعاملة
     * لا من الكاش وحده. الذرّية ذاتها (فشل وسط المعاملة ⇒ تراجع كلي) تُختبر في
     * `:core:database` فوق التنفيذ الحقيقي (`ClassificationRestoreAtomicityTest`).
     */
    private class FakeClassificationRestoreRunner(
        private val contactDao: FakeContactDao,
        private val deleteBeforeRewrites: Set<Long> = emptySet()
    ) : ClassificationRestoreRunner {
        var lastPlan: ClassificationRestorePlan? = null
            private set

        override suspend fun applyClassificationRestore(plan: ClassificationRestorePlan): ClassificationRestoreOutcome {
            lastPlan = plan
            var cleared = 0
            val appliedIds = mutableListOf<Long>()
            val missingIds = mutableListOf<Long>()

            // ترتيب التنفيذ الحقيقي: شطف REPLACE كاملاً قبل أي نسخة موحّدة.
            if (plan.replaceBeforeApply) {
                for (replacement in plan.replacementContacts) {
                    contactDao.contactsMap[replacement.id] = replacement
                    cleared++
                }
            }

            for (rewrite in plan.contactRewrites) {
                if (rewrite.id in deleteBeforeRewrites) {
                    contactDao.contactsMap.remove(rewrite.id)
                    missingIds += rewrite.id
                    continue
                }
                if (rewrite.id !in contactDao.contactsMap) {
                    missingIds += rewrite.id
                    continue
                }
                contactDao.contactsMap[rewrite.id] = rewrite
                appliedIds += rewrite.id
            }

            return ClassificationRestoreOutcome(
                clearedContacts = cleared,
                appliedContactIds = appliedIds,
                missingContactIds = missingIds
            )
        }
    }

    private fun createContact(
        id: Long,
        lookupKey: String,
        displayName: String,
        category: Category = Category.UNCATEGORIZED,
        tags: List<String> = emptyList(),
        arham: ArhamInfo? = null,
        dates: List<ImportantDate> = emptyList()
    ): Contact = Contact(
        id = id,
        systemContactId = lookupKey,
        lookupKey = lookupKey,
        name = ContactName(displayName = displayName),
        category = category,
        tags = tags,
        arham = arham,
        dates = dates
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
            arhamDao = fakeArhamDao,
            disputeLogDao = FakeDisputeLogEntityDao()
        )

        val payload = repository.getBackupData()

        assertEquals(1, payload.version)
        assertEquals(2, payload.categories.size)
        assertEquals(1, payload.arham.size)
        assertEquals(2, payload.tags.size)
        assertEquals(AppThemeMode.DARK, payload.preferences?.themeMode)
    }

    /**
     * تذكرة #61 (S1-5): قراءة النزاعات للتصدير SSOT حصراً — وغياب DAO القراءة
     * يرفع فشلاً فورياً بدل قائمة كلية فارغة (السيناريوهان في اختبار واحد
     * حفاظاً على سقف دوال الفئة في detekt).
     */
    @Test
    fun getBackupDataReadsDisputeLogsFromRoomOrFailFastOnMissingDao() = runTest {
        val fakeDisputeDao = FakeDisputeLogEntityDao(
            listOf(
                DisputeLogEntity(
                    id = 1L,
                    serviceCloudId = "srv_1",
                    description = "تأخر في الموعد",
                    status = DisputeStatus.OPEN,
                    createdAt = 1_000L,
                    updatedAt = 2_000L
                ),
                DisputeLogEntity(
                    id = 2L,
                    serviceCloudId = "srv_2",
                    description = "حُلّت بالاتفاق",
                    status = DisputeStatus.RESOLVED,
                    createdAt = 1_100L,
                    updatedAt = 1_500L,
                    resolvedAt = 1_400L
                )
            )
        )
        val wired = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = FakeContactDao(
                listOf(createContact(id = 1, lookupKey = "lookup_1", displayName = "أحمد"))
            ),
            disputeLogDao = fakeDisputeDao
        )

        val payload = wired.getBackupData()

        assertEquals(2, payload.disputes.size, "الحمولة تقرأ النزاعات من Room حصراً لا من قائمة مدوّنة")
        assertEquals(setOf("srv_1", "srv_2"), payload.disputes.map { it.serviceCloudId }.toSet())
        assertEquals(
            setOf(DisputeStatus.OPEN, DisputeStatus.RESOLVED),
            payload.disputes.map { it.status }.toSet()
        )
        assertEquals(
            "تأخر في الموعد",
            payload.disputes.first { it.serviceCloudId == "srv_1" }.description
        )

        val unwired = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = FakeContactDao(
                listOf(createContact(id = 1, lookupKey = "lookup_1", displayName = "سعاد"))
            )
        )
        val failure = runCatching { unwired.getBackupData() }
        assertTrue(failure.isFailure, "التصدير بلا DAO نزاعات يتوقف فوراً — لا بيانات كلية مُبهتة")
    }

    @Test
    fun restoreBackupData_mergeMode_updatesMatchedAndPreservesRest() = runTest {
        val existing1 = createContact(id = 1, lookupKey = "lookup_1", displayName = "أحمد", category = Category.UNCATEGORIZED)
        val existing2 = createContact(id = 2, lookupKey = "lookup_2", displayName = "سارة", category = Category.WORK)

        val fakeContactDao = FakeContactDao(listOf(existing1, existing2))
        val fakeArhamDao = FakeArhamDao()
        val fakeRunner = FakeClassificationRestoreRunner(fakeContactDao)
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao,
            classificationRestoreRunner = fakeRunner
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

        // شكل الخطة في وضع الدمج: بلا شطف (replacements فارغة) وكتابات موحّدة للجهات الملموسة حصراً.
        val plan = checkNotNull(fakeRunner.lastPlan)
        assertEquals(false, plan.replaceBeforeApply)
        assertEquals(0, plan.replacementContacts.size)
        assertEquals(listOf(1L), plan.contactRewrites.map { it.id })
        // التحقق من تحديث جهة الاتصال 1 (النسخة الموحّدة: تصنيف + أرحام + وسوم في كتابة واحدة)
        val updated1 = fakeContactDao.getContactById(1)
        assertEquals(Category.FAMILY, updated1?.category)
        assertEquals(listOf("عائلة", "طبيب"), updated1?.tags)
        assertEquals(Relation.BROTHER, updated1?.arham?.relation)
        // كتابات الأرحام المنفصلة لم تعد موجودة بعد اليوم — الممر الذرّي وحده الكاتب
        assertTrue(fakeArhamDao.arhamMap.isEmpty(), "Arham writes must flow through the restore transaction, not upsertArhamInfo")

        // التحقق من بقاء جهة الاتصال 2 دون تغيير في وضع الدمج
        val unchanged2 = fakeContactDao.getContactById(2)
        assertEquals(Category.WORK, unchanged2?.category)
    }

    @Test
    fun restoreBackupData_replaceMode_resetsPreviousCategoriesAndTags() = runTest {
        val birthday = ImportantDate(type = "BIRTHDAY", month = 3, day = 14)
        val existing1 = createContact(id = 1, lookupKey = "lookup_1", displayName = "أحمد", category = Category.WORK, tags = listOf("قديم"))
        val existing2 = createContact(
            id = 2,
            lookupKey = "lookup_2",
            displayName = "سارة",
            category = Category.FRIENDS,
            tags = listOf("جامعة"),
            dates = listOf(birthday)
        )

        val fakeContactDao = FakeContactDao(listOf(existing1, existing2))
        val fakeArhamDao = FakeArhamDao()
        val fakeRunner = FakeClassificationRestoreRunner(fakeContactDao)
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao,
            classificationRestoreRunner = fakeRunner
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

        // شكل الخطة في وضع REPLACE: نسخ محو كاملة لكل الجهات الحية (تصنيف عطيف/
        // وسوم فارغة/بلا أرحام) مع حفظ ما لا تملكه الحمولة (التواريخ والأطفال)،
        // وأعلى منها نسخة موحّدة نهائية للجهة الملموسة تحمل التصنيف الجديد.
        val plan = checkNotNull(fakeRunner.lastPlan)
        assertTrue(plan.replaceBeforeApply)
        assertEquals(listOf(1L, 2L), plan.replacementContacts.map { it.id })
        assertTrue(
            plan.replacementContacts.all { it.category == Category.UNCATEGORIZED && it.tags.isEmpty() && it.arham == null },
            "REPLACE replacement copies must reset classification on top of preserved identity"
        )
        assertEquals(listOf(1L), plan.contactRewrites.map { it.id })
        assertEquals(Category.FAMILY, plan.contactRewrites.first().category)

        // جهة 1 تأخذ التصنيف الجديد من النسخة (الوسوم القديمة شُطبت بلا تنازل)
        assertEquals(Category.FAMILY, fakeContactDao.getContactById(1)?.category)
        assertTrue(fakeContactDao.getContactById(1)?.tags?.isEmpty() == true)
        // جهة 2 لم تكن في النسخة فيتم تصفير تصنيفها ووسومها دون مساس تواريخها (عهد المسار القديم)
        assertEquals(Category.UNCATEGORIZED, fakeContactDao.getContactById(2)?.category)
        assertTrue(fakeContactDao.getContactById(2)?.tags?.isEmpty() == true)
        assertEquals(listOf(birthday), fakeContactDao.getContactById(2)?.dates)
        assertTrue(fakeArhamDao.arhamMap.isEmpty())
    }

    @Test
    fun restoreBackupData_withRestorePreferencesTrue_restoresSettings() = runTest {
        val fakeContactDao = FakeContactDao()
        val fakeArhamDao = FakeArhamDao()
        val fakeRunner = FakeClassificationRestoreRunner(fakeContactDao)
        val dataSource = SilaPreferencesDataSource()

        val repository = SettingsRepositoryImpl(
            dataSource = dataSource,
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao,
            classificationRestoreRunner = fakeRunner
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

        // التفضيلات (ونغمات التصنيفات المرتبطة بها — ADR-0015) خارج المعاملة حصراً:
        // الدفتر فارغ فالخطة بلا شطف ولا كتابات، وتُنفَّذ DataStore بعد COMMIT.
        val plan = checkNotNull(fakeRunner.lastPlan)
        assertEquals(false, plan.replaceBeforeApply)
        assertEquals(0, plan.contactRewrites.size)
    }

    @Test
    fun restoreBackupData_withoutRestoreRunner_failsFastInsteadOfPartialRestore() = runTest {
        val fakeContactDao = FakeContactDao(
            listOf(createContact(1, "lookup_1", "أحمد", category = Category.WORK, tags = listOf("قدم")))
        )
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = fakeContactDao,
            arhamDao = FakeArhamDao()
        )

        val result = runCatching {
            repository.restoreBackupData(
                payload = BackupPayload(
                    version = 1,
                    exportedAt = "2026-11-08T12:00:00Z",
                    appVersion = "1.0.0",
                    categories = listOf(
                        BackupCategoryEntry(lookupKey = "lookup_1", displayName = "أحمد", category = Category.FAMILY)
                    )
                ),
                mode = RestoreMode.REPLACE,
                restorePreferences = false
            )
        }

        assertTrue(result.isFailure, "Missing ClassificationRestoreRunner must fail fast (partial restore was the defect)")
        // الحرس سابق للخطة والشطف: لا مسح REPLACE نصف منفَّذ ولا كتابة جزئية.
        assertEquals(Category.WORK, fakeContactDao.getContactById(1)?.category)
        assertEquals(listOf("قدم"), fakeContactDao.getContactById(1)?.tags)
    }

    @Test
    fun restoreBackupData_contactVanishedMidRestore_entriesDerivedFromOutcome() = runTest {
        val existing1 = createContact(1, "lookup_1", "أحمد")
        val fakeContactDao = FakeContactDao(listOf(existing1))
        // محاكاة قبر/غياب يُكتشف داخل المعاملة حصراً (نمط getLiveContactCreatedAt):
        // الممرّ يعلن المعرف محدوماً والنطاق يربط مدخلاته به كغير-مطابقة.
        val fakeRunner = FakeClassificationRestoreRunner(
            contactDao = fakeContactDao,
            deleteBeforeRewrites = setOf(1L)
        )
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = fakeContactDao,
            arhamDao = FakeArhamDao(),
            classificationRestoreRunner = fakeRunner
        )
        val payload = BackupPayload(
            version = 1,
            exportedAt = "2026-11-08T12:00:00Z",
            appVersion = "1.0.0",
            categories = listOf(
                BackupCategoryEntry(lookupKey = "lookup_1", displayName = "أحمد", category = Category.FAMILY)
            ),
            arham = listOf(
                BackupArhamEntry(lookupKey = "lookup_1", relation = Relation.BROTHER, frequency = ArhamFrequency.WEEKLY)
            ),
            tags = listOf(BackupTagEntry(lookupKey = "lookup_1", tags = listOf("طبيب"))),
            preferences = null,
            categoryRingtones = emptyMap(),
            automations = emptyList(),
            disputes = emptyList()
        )

        val result = repository.restoreBackupData(payload, mode = RestoreMode.MERGE, restorePreferences = false)

        // مدخلات الحمولة الموجّهة للجهة المحدومة داخل المعاملة كلها غير مطابقة
        // (احتسابها من ناتج المعاملة لا من الكاش الحي).
        assertEquals(0, result.matchedCategories)
        assertEquals(1, result.unmatchedCategories)
        assertEquals(0, result.matchedArham)
        assertEquals(1, result.unmatchedArham)
        assertEquals(1, result.unmatchedEntries.size)
        // وتحقق العدم داخل المعاملة منع أي كتابة موحّدة (لا قبر ثم كتابة).
        assertFalse(fakeContactDao.contactsMap.containsKey(1L))
    }

    @Test
    fun getBackupData_withoutContactsDao_failsFastInsteadOfSilentEmptyBackup() = runTest {
        val repository = SettingsRepositoryImpl(SilaPreferencesDataSource())

        val result = runCatching { repository.getBackupData() }

        assertTrue(result.isFailure, "Missing ContactDao must fail fast (silent empty backup was the defect)")
    }

    @Test
    fun restoreBackupData_withoutDaos_failsFastInsteadOfSilentNoOpRestore() = runTest {
        val repository = SettingsRepositoryImpl(SilaPreferencesDataSource())

        val result = runCatching {
            repository.restoreBackupData(
                payload = BackupPayload(
                    version = 1,
                    exportedAt = "2026-11-08T12:00:00Z",
                    appVersion = "1.0.0"
                ),
                mode = RestoreMode.MERGE,
                restorePreferences = false
            )
        }

        assertTrue(result.isFailure, "Missing DAOs must fail fast (silent no-op restore was the defect)")
    }

    @Test
    fun searchLinkCandidates_shortQuery_returnsEmptyWithoutConsumingSearch() = runTest {
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = FakeContactDao(listOf(createContact(1, "lookup_1", "سعاد"))),
            arhamDao = FakeArhamDao()
        )

        assertEquals(0, repository.searchLinkCandidates("").size)
        assertEquals(0, repository.searchLinkCandidates(" س ").size)
        assertEquals(0, repository.searchLinkCandidates("س").size)
    }

    @Test
    fun searchLinkCandidates_validQuery_delegatesToContactsDaoSearch() = runTest {
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = FakeContactDao(
                listOf(
                    createContact(1, "lookup_1", "سعاد"),
                    createContact(2, "lookup_2", "أحمد")
                )
            ),
            arhamDao = FakeArhamDao()
        )

        val candidates = repository.searchLinkCandidates("سع")

        assertEquals(1, candidates.size)
        assertEquals("سعاد", candidates.first().name.displayName)
    }

    @Test
    fun linkUnmatchedEntry_missingTargetContact_returnsFalse() = runTest {
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = FakeContactDao(),
            arhamDao = FakeArhamDao()
        )
        val entry = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = Category.FAMILY,
            arhamRelation = null
        )

        val linked = repository.linkUnmatchedEntry(entry, targetContactId = 99)

        assertFalse(linked, "Missing target contact must surface as false, not a silent no-op")
    }

    @Test
    fun linkUnmatchedEntry_appliesCategoryAndArhamToTargetContact() = runTest {
        val fakeContactDao = FakeContactDao(listOf(createContact(7, "lookup_7", "سعاد")))
        val fakeArhamDao = FakeArhamDao()
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )
        val entry = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = Category.FAMILY,
            arhamRelation = Relation.AUNT_MATERNAL
        )

        val linked = repository.linkUnmatchedEntry(entry, targetContactId = 7)

        assertTrue(linked)
        assertEquals(Category.FAMILY, fakeContactDao.contactsMap[7]?.category)
        assertEquals(Relation.AUNT_MATERNAL, fakeArhamDao.arhamMap[7]?.relation)
        // التكرار المحايد الافتراضي بعد الربط اليدوي (المستخدم يعدّله لاحقاً من شاشة الأرحام)
        assertEquals(ArhamFrequency.OCCASIONS, fakeArhamDao.arhamMap[7]?.frequency)
    }

    @Test
    fun linkUnmatchedEntry_entryWithoutCategoryOrRelation_returnsFalseWithoutWrites() = runTest {
        val fakeContactDao = FakeContactDao(listOf(createContact(7, "lookup_7", "سعاد")))
        val fakeArhamDao = FakeArhamDao()
        val repository = SettingsRepositoryImpl(
            dataSource = SilaPreferencesDataSource(),
            contactDao = fakeContactDao,
            arhamDao = fakeArhamDao
        )
        val entry = UnmatchedBackupEntry(
            lookupKey = "old_key",
            displayName = "منى",
            category = null,
            arhamRelation = null
        )

        val linked = repository.linkUnmatchedEntry(entry, targetContactId = 7)

        assertFalse(linked)
        assertEquals(Category.UNCATEGORIZED, fakeContactDao.contactsMap[7]?.category)
        assertTrue(fakeArhamDao.arhamMap.isEmpty())
    }
}
