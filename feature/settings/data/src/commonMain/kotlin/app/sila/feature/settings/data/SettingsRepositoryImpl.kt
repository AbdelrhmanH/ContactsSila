package app.sila.feature.settings.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ClassificationRestorePlan
import app.sila.core.database.dao.ClassificationRestoreRunner
import app.sila.core.database.dao.ContactDao
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
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
import kotlinx.datetime.Clock

/**
 * تنفيذ مستودع الإعدادات والنسخ الاحتياطي لـ ContactsSila (T9 — Issue #30).
 * يدير التفضيلات عبر DataStore وقراءة/استعادة بيانات التصنيفات وصلة الرحم والوسوم عبر Room DAOs.
 * يضمن تطابق النقل المشفر وعدم إرسال أي بيانات شخصية (Privacy-First).
 */
class SettingsRepositoryImpl(
    private val dataSource: UserPreferencesDataSource,
    private val contactDao: ContactDao? = null,
    private val arhamDao: ArhamDao? = null,
    /**
     * ممرّ الذرّية (S1-1 · تذكرة #60 · [ADR-0015](docs/adr/0015-atomic-restore-transaction-passage.md)):
     * الاستعادة تُنفَّذ عبر واجهة معاملات في `:core:database` — المعاملة واحدة
     * فوق `LocalSilaDatabase` بلا تسريب Room إلى طبقة data هذه. غيابه يجعل
     * `restoreBackupData` ترفع فشلاً فورياً (نمط حراسة DAO هنا نفسها) فلا
     * استعادة جزئية صامتة أبداً.
     */
    private val classificationRestoreRunner: ClassificationRestoreRunner? = null
) : SettingsRepository {

    private fun requireContactsDao(): ContactDao =
        requireNotNull(contactDao) { "Backup export/restore requires ContactDao wiring (silent no-op guard)" }

    private fun requireArhamDao(): ArhamDao =
        requireNotNull(arhamDao) { "Backup restore requires ArhamDao wiring (silent no-op guard)" }

    private fun requireRestoreRunner(): ClassificationRestoreRunner =
        requireNotNull(classificationRestoreRunner) {
            "Backup restore requires ClassificationRestoreRunner wiring (atomic all-or-nothing passage)"
        }

    override val preferences: Flow<UserPreferences> get() = dataSource.preferences

    override suspend fun setThemeMode(mode: AppThemeMode) = dataSource.setThemeMode(mode)
    override suspend fun setLanguage(language: AppLanguage) = dataSource.setLanguage(language)
    override suspend fun setDigits(digits: AppDigits) = dataSource.setDigits(digits)
    override suspend fun setNameOrder(order: NameOrder) = dataSource.setNameOrder(order)
    override suspend fun setDefaultTab(tab: DefaultTab) = dataSource.setDefaultTab(tab)
    override suspend fun setVisibleAccounts(accounts: Set<String>) = dataSource.setVisibleAccounts(accounts)
    override suspend fun setDefaultSimSlot(slot: Int?) = dataSource.setDefaultSimSlot(slot)
    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) = dataSource.setArhamNotificationsEnabled(enabled)
    override suspend fun setQuietHours(startHour: Int, endHour: Int) = dataSource.setQuietHours(startHour, endHour)
    override suspend fun setArhamNotificationTime(hour: Int, minute: Int) = dataSource.setArhamNotificationTime(hour, minute)
    override suspend fun setArhamMaxPerDay(max: Int) = dataSource.setArhamMaxPerDay(max)
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
    override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) =
        dataSource.setSwipeActions(start, end)
    override suspend fun setCategoryRingtone(category: Category, uri: String?) =
        dataSource.setCategoryRingtone(category, uri)
    override suspend fun setCategoryRingtones(ringtones: Map<Category, String>) =
        dataSource.setCategoryRingtones(ringtones)

    override suspend fun getBackupData(): BackupPayload {
        val dao = requireContactsDao()
        val currentPrefs = dataSource.preferences.first()
        val allContacts = dao.observeContacts().first()

        val categoryEntries = allContacts.filter { it.category != Category.UNCATEGORIZED }.map { c ->
            BackupCategoryEntry(
                lookupKey = c.lookupKey ?: c.systemContactId,
                displayName = c.name.displayName,
                category = c.category
            )
        }

        val arhamEntries = allContacts.filter { it.arham != null }.map { c ->
            val arham = c.arham!!
            val parentLookupKey = arham.parentContactId?.let { pid ->
                allContacts.find { it.id == pid }?.let { it.lookupKey ?: it.systemContactId }
            }
            BackupArhamEntry(
                lookupKey = c.lookupKey ?: c.systemContactId,
                relation = arham.relation,
                frequency = arham.frequency,
                layer = arham.layer,
                parentLookupKey = parentLookupKey,
                lastContactedAt = arham.lastContactedAt,
                pausedUntil = arham.pausedUntil,
                dates = c.dates
            )
        }

        val tagEntries = allContacts.filter { it.tags.isNotEmpty() }.map { c ->
            BackupTagEntry(
                lookupKey = c.lookupKey ?: c.systemContactId,
                tags = c.tags
            )
        }

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
            exportedAt = Clock.System.now().toString(),
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

    /**
     * استعادة التصنيفات والأرحام والوسوم — S1-1 (تذكرة #60 · [ADR-0015](docs/adr/0015-atomic-restore-transaction-passage.md)):
     * عقد «كل-أو-لا-شيء» عبر [ClassificationRestoreRunner] — معاملة Room واحدة في
     * `:core:database` (بلا تسريب Room إلى طبقة data هذه). الخطة تُبنى هنا من
     * قراءة لحظية واحدة (كاش الاتصالات + الحمولة المفكوكة حصراً قبل المعاملة)
     * أصنافاً صفية (`:core:model` — بلا أنواع الحمولة في النطاق المشترك)،
     * والنتيجة تُحتسب من ناتج المعاملة (المطبَّق فعلياً والمحدوم داخلياً) لا من
     * حلّ الكاش وحده.
     *
     * عهد الدلالة المحفوظ حرفاً من المسار القديم:
     * - شطف REPLACE: تصنيف عطيف/وسوم فارغة/بلا أرحام لكل الجهات الحية ولا يمس
     *   التواريخ ولا الأطفال ولا الحقول القياسية.
     * - إسناد أرحام يُلزم التصنيف إلى `FAMILY` (عهد `ArhamDao.upsertArhamInfo`)
     *   ويستبدل التواريخ حصراً حين تقدّم الحمولة تواريخاً غير فارغة.
     * - استبعاد يدوي/تعويض ربط يمسّ تصنيفًا وصلةً رحم معاً على طبقة العلاقة ذاتها.
     * - **دلالة `restorePreferences` الموثّقة:** `true` يستعيد تفضيلات الحمولة
     *   **ونغمات التصنيفات `categoryRingtones` معها** (ملف الترابط نفسه — سلوك
     *   كان في الكود بلا تدوين منذ T9 ويُدنّ الآن موسّعاً في `docs/features/07`).
     * - تفضيلات DataStore تُنفَّذ **بعد COMMIT حصراً**: فشل المعاملة يبقي
     *   التفضيلات بلا مساس، وفشل DataStore اللاحق لا يبقي قاعدة نصف مستعادة.
     */
    override suspend fun restoreBackupData(
        payload: BackupPayload,
        mode: RestoreMode,
        restorePreferences: Boolean
    ): RestoreResult {
        val contactDao = requireContactsDao()
        requireArhamDao()
        val restoreRunner = requireRestoreRunner()
        val allContacts = contactDao.observeContacts().first()
        val contactByLookup = mutableMapOf<String, Long>()
        for (c in allContacts) {
            c.lookupKey?.let { contactByLookup[it] = c.id }
            contactByLookup[c.systemContactId] = c.id
        }
        val contactById = allContacts.associateBy { it.id }

        val replaceBeforeApply = mode == RestoreMode.REPLACE
        // قاعدة النسخ الموحّدة: REPLACE يبني من نسخ المحو (فلا تعود وسوم/أرحام
        // قديمة خارج الحمولة فوق الشطف) وMERGE من الحالة القائمة حرفاً.
        val replaceBaseById: Map<Long, Contact> = if (replaceBeforeApply) {
            allContacts.associate { it.id to it.copy(category = Category.UNCATEGORIZED, tags = emptyList(), arham = null) }
        } else {
            emptyMap()
        }

        val unifiedById = mutableMapOf<Long, Contact>()
        fun unifiedBase(contactId: Long): Contact? =
            unifiedById[contactId]
                ?: replaceBaseById[contactId]
                ?: contactById[contactId]

        val categoryEntryById = mutableListOf<Pair<BackupCategoryEntry, Long?>>()
        for (cat in payload.categories) {
            val contactId = contactByLookup[cat.lookupKey]
            categoryEntryById += cat to contactId
            val base = contactId?.let { unifiedBase(it) } ?: continue
            unifiedById[contactId] = base.copy(category = cat.category)
        }

        val arhamEntryById = mutableListOf<Pair<BackupArhamEntry, Long?>>()
        for (arham in payload.arham) {
            val contactId = contactByLookup[arham.lookupKey]
            arhamEntryById += arham to contactId
            val base = contactId?.let { unifiedBase(it) } ?: continue
            unifiedById[contactId] = base.copy(
                category = Category.FAMILY,
                arham = ArhamInfo(
                    relation = arham.relation,
                    layer = arham.layer,
                    parentContactId = arham.parentLookupKey?.let { contactByLookup[it] },
                    frequency = arham.frequency,
                    lastContactedAt = arham.lastContactedAt,
                    pausedUntil = arham.pausedUntil
                ),
                dates = if (arham.dates.isNotEmpty()) arham.dates else base.dates
            )
        }

        val tagEntryById = mutableListOf<Pair<BackupTagEntry, Long?>>()
        for (tagEntry in payload.tags) {
            val contactId = contactByLookup[tagEntry.lookupKey]
            tagEntryById += tagEntry to contactId
            val base = contactId?.let { unifiedBase(it) } ?: continue
            unifiedById[contactId] = base.copy(tags = tagEntry.tags)
        }

        val outcome = restoreRunner.applyClassificationRestore(
            ClassificationRestorePlan(
                replaceBeforeApply = replaceBeforeApply,
                replacementContacts = if (replaceBeforeApply) {
                    replaceBaseById.values.toList()
                } else {
                    emptyList()
                },
                contactRewrites = unifiedById.entries.sortedBy { it.key }.map { it.value }
            )
        )

        // نتيجة كل قسم من ناتج المعاملة: المعرف الملصق بكل مدخل يُصنَّف مطبَّقاً
        // أو محدوماً (الانحراف الوحيد المُرسَم — انتهت الجهة قيد المعاملة)؛
        // ترتيب الحمولة الأصلي محفوظ في قوائم غير-المطابقة كما في المسار القديم.
        val appliedIds = outcome.appliedContactIds.toSet()
        val missingIds = outcome.missingContactIds.toSet()

        var matchedCategoriesCount = 0
        val unmatchedCategories = mutableListOf<BackupCategoryEntry>()
        for ((cat, contactId) in categoryEntryById) {
            when {
                contactId == null || contactId in missingIds -> unmatchedCategories += cat
                contactId in appliedIds -> matchedCategoriesCount++
                else -> unmatchedCategories += cat
            }
        }

        var matchedArhamCount = 0
        val unmatchedArhamList = mutableListOf<BackupArhamEntry>()
        for ((arham, contactId) in arhamEntryById) {
            when {
                contactId == null || contactId in missingIds -> unmatchedArhamList += arham
                contactId in appliedIds -> matchedArhamCount++
                else -> unmatchedArhamList += arham
            }
        }

        // استعادة الوسوم مسؤولة الكتابة للممر الذرّي أعلاه (نسخ موحّدة)؛ مدخلاتها
        // غير المطابقة لم تُعرض أبداً في نتيجة الاستعادة القديمة — نفس العهد بلا مساس.

        // استعادة التفضيلات إذا طلب المستخدم صراحة ذلك (AC: لا تُستعاد التفضيلات تلقائياً)
        // — بعد COMMIT حصراً (ADR-0015): فشل المعاملة يبقي DataStore بلا مساس،
        // وفشل DataStore اللاحق لا يترك قاعدة نصف مستعادة (المعاملة سبقت كل كتابة).
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
                dataSource.setArhamOccasionDayBeforeEnabled(p.arhamOccasionDayBeforeEnabled)
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

        // بناء قائمة الإدخالات غير المتطابقة شاملة الفئات وصلة الرحم
        val unmatchedMap = mutableMapOf<String, UnmatchedBackupEntry>()
        val arhamMap = payload.arham.associateBy { it.lookupKey }

        for (un in unmatchedCategories) {
            unmatchedMap[un.lookupKey] = UnmatchedBackupEntry(
                lookupKey = un.lookupKey,
                displayName = un.displayName,
                category = un.category,
                arhamRelation = arhamMap[un.lookupKey]?.relation
            )
        }

        for (unArham in unmatchedArhamList) {
            if (!unmatchedMap.containsKey(unArham.lookupKey)) {
                unmatchedMap[unArham.lookupKey] = UnmatchedBackupEntry(
                    lookupKey = unArham.lookupKey,
                    displayName = unArham.lookupKey,
                    category = null,
                    arhamRelation = unArham.relation
                )
            }
        }

        return RestoreResult(
            matchedCategories = matchedCategoriesCount,
            unmatchedCategories = unmatchedCategories.size,
            matchedArham = matchedArhamCount,
            unmatchedArham = unmatchedArhamList.size,
            restoredPreferences = restorePreferences,
            unmatchedEntries = unmatchedMap.values.toList()
        )
    }

    /**
     * S1-15 (تذكرة #62): بحث مرشّحي الربط اليدوي — قراءة Room حصراً عبر عقد DAO.
     * استعلام أقصر من حرفين لا يستهلكها (قائمة غير عملية على كامل الدفتر).
     */
    override suspend fun searchLinkCandidates(query: String): List<Contact> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return requireContactsDao().searchContacts(trimmed).first()
    }

    /**
     * S1-15 (تذكرة #62): تطبيق إدخال غير مطابق على جهة اختيارها المستخدم يدوياً —
     * التصنيف عبر `updateCategory` وصلة الرحم عبر `upsertArhamInfo` بطبقة العلاقة
     * وتكرار `OCCASIONS` المحايد. الفشل معلوم قيمياً (false) لا صمتاً: غياب جهة
     * الهدف أو سجل بلا تصنيف وبلا صلة رحم يعيدان false.
     */
    override suspend fun linkUnmatchedEntry(
        entry: UnmatchedBackupEntry,
        targetContactId: Long
    ): Boolean {
        val dao = requireContactsDao()
        val target = dao.getContactById(targetContactId) ?: return false

        var applied = false
        entry.category?.let { category ->
            dao.updateCategory(target.id, category)
            applied = true
        }
        entry.arhamRelation?.let { relation ->
            requireArhamDao().upsertArhamInfo(
                contactId = target.id,
                info = ArhamInfo(
                    relation = relation,
                    frequency = ArhamFrequency.OCCASIONS
                )
            )
            applied = true
        }
        return applied
    }
}
