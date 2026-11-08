package app.sila.core.database.dao

import androidx.room3.PooledConnection
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.common.AppDispatchers
import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.common.PhoneNormalizer
import app.sila.core.database.SilaRoomDatabase
import app.sila.core.database.SilaRoomDatabaseConstructor
import app.sila.core.database.SILA_MIGRATIONS
import app.sila.core.database.SILA_ROOM_VERSION
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactAddressEntity
import app.sila.core.database.entity.ContactCustomFieldEntity
import app.sila.core.database.entity.ContactDateEntity
import app.sila.core.database.entity.ContactEmailEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactMergeSnapshotEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactWebsiteEntity
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.DuplicateDismissalEntity
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.database.entity.SmartFilterFeedbackEntity
import app.sila.core.database.entity.toContactEntity
import app.sila.core.database.entity.toCallEvent
import app.sila.core.database.entity.toCallEventEntity
import app.sila.core.database.entity.toDomain
import app.sila.core.database.entity.toEntity
import app.sila.core.database.entity.toWebsiteEntity
import app.sila.core.database.legacy.LegacyAdoptionException
import app.sila.core.database.legacy.LegacyDatabaseAdopter
import app.sila.core.database.legacy.LegacyFtsDdl
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.database.legacy.SilaDatabaseBackup
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DisputeStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.RingingStatus
import app.sila.core.model.Phone
import app.sila.core.model.RecentInteraction
import app.sila.core.model.Relation
import app.sila.core.model.Service
import app.sila.core.model.ServiceVisibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * واجهة قاعدة البيانات المحلية — **الطبقة الخارجية (facade) فوق Room 3 KMP** كصاحب مخطط
 * وحيد (Single Source of Truth — ARCHITECTURE.md §5 · ADR-0011).
 *
 * PR B (F-04/F-05) — قواعد ADR-0011 الصارمة:
 * - **المخطط:** `SilaRoomDatabase` (`@Database(version = SILA_ROOM_VERSION)` — v1→v2 ترحيل
 *   T6 غير هدمي) هو المنتِج الوحيد للجداول عبر KSP — لا CREATE TABLE يدوي هنا إطلاقاً.
 *   التثبيت الجديد تنشئ Room مباشرة.
 * - **الاعتماد الآمن:** قواعد المستخدمين القديمة (`user_version = 0`) تُعتمد قبل فتح Room
 *   عبر [LegacyDatabaseAdopter]: بصمة بنية → تصنيف صارم → نسخة واقية موثقة
 *   (`sila_local.pre-room-backup` — VACUUM INTO بلا نسخ خام تحت WAL) → شيم ضيق داخل
 *   معاملة واحدة → Room يتحقق ويسجل إصداره الحالي (`SILA_ROOM_VERSION`) عند أول فتح.
 *   ليست `Migration(0,1)`.
 *   النسخة الواقية تُحذف بعد أول عملية Room ناجحة (اعتماد موثق) حصراً.
 * - **مسار الكتابة المنقسم:** الكتابات المركّبة (upsertContact/deleteContact(s)/البذر/
 *   applySyncBatch/cleanupExpiredData) بـ SQL خام **حرفي** داخل
 *   `useWriterConnection { immediateTransaction { … } }` مع خطافات فشل الاختبار قبل
 *   COMMIT؛ والقراءات والكتابات أحادية العبارة عبر DAOs كيانات Room.
 * - **SSOT + الكاش:** الكتابات المركّبة تُحدِّث كاش الذاكرة الذي تعرضه الواجهات **بعد نجاح
 *   COMMIT** حصراً؛ أما التحديثات أحادية الهدف (`updateCategory`/`setFavorite`/
 *   `upsertArhamInfo`) فتبقي الترتيب التاريخي للكتابة أولاً-كاش كما في
 *   الأساس `dd04f1f` حرفياً (بقي `dbMutex` لاتساق الكاش).
 * - **زرع العرض (F-01):** لا تُزرع أي بيانات تجريبية أبداً — البذر متاح للاختبارات عبر
 *   تصريح صريح بـ [seedDemoData]، ولا تُكتب إلا في جداول فارغة.
 * - **جدول FTS4:** `contacts_fts` ليس كياناً مُداراً من Room — KSP على أهداف Native
 *   لا يعالج كيانات `@Fts4` المستقلة (خلل Room 3.0.3 موثق في ADR-0011 §FTS4)؛ يُنشأ
 *   عبر `RoomDatabase.Callback` بـ DDL تراثي حرفي واحد ([LegacyFtsDdl])، والبحث
 *   والمزامنة عبر SQL خام في هذه الواجهة.
 */
/** مراحل التحقق أثناء التهيئة/الاعتماد — للاختبارات الحتمية لحقن الفشل (SR-04). */
internal enum class AdoptionVerificationStage {
    ROOM_OPEN,
    CONTACTS_READ,
    CONTACT_PHONES_READ,
    ARHAM_READ,
    SERVICES_READ,
    VERSION_CHECK,
    INTEGRITY_CHECK,
    FTS_CHECK,
    BEFORE_BACKUP_DELETE,
    BACKUP_DELETE,
}

class LocalSilaDatabase internal constructor(
    /** مسار ملف قاعدة البيانات — `":memory:"` حصرياً كحاضنة اختبارات (لا مسار إنتاج بديل). */
    val dbFilePath: String,
    private val dispatchers: AppDispatchers,
    /** بذر بيانات العرض في الجداول الفارغة — للاختبارات حصراً (افتراضياً معطَّل — F-01). */
    private val seedDemoData: Boolean,
    private val adoptionStageHook: ((AdoptionVerificationStage) -> Unit)?,
    private val backupDeleteOverride: (() -> Boolean)?,
) : ContactDao, ArhamDao, ServiceDao, DataRetentionCleanupDao, ContactsSyncDao, TopFiveMaterializationDao, EmergencySessionDao {

    constructor(
        dbFilePath: String = ":memory:",
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
        seedDemoData: Boolean = false,
    ) : this(
        dbFilePath = dbFilePath,
        dispatchers = dispatchers,
        seedDemoData = seedDemoData,
        adoptionStageHook = null,
        backupDeleteOverride = null,
    )

    private val dbMutex = Mutex()

    // ===== خطاف «تغيّرت بيانات أرحام» (T8-10 · D3: تحديثات مُحرَّكة بالتغيير) =====

    /** مستمعو تغيّر بيانات أرحام — يُستدعون بعد COMMIT نجاح كتابة مؤثرة (قراءة/كتابة آمنة). */
    private val arhamDataChangeListeners = MutableStateFlow<List<ArhamDataChangeListener>>(emptyList())

    /** تسجيل مستمع تغيّر (idempotent: التسجيل المكرر بلا أثر). */
    fun addArhamDataChangeListener(listener: ArhamDataChangeListener) {
        // حاملٌ ذرّي قائم على StateFlow (مراجعة T8-10 · P3-1، وإصلاح ترجمة iOS — T8-12):
        // قفلُ JVM `synchronized` غير موجود في commonMain — الاستبدال تحديثُ CAS ذرّياً
        // على قائمة ثابتة: تسجيل/إلغاء من خيوط مختلفة بأمان وبلا مسدسين.
        arhamDataChangeListeners.update { current -> if (listener in current) current else current + listener }
    }

    /** إلغاء تسجيل مستمع تغيّر. */
    fun removeArhamDataChangeListener(listener: ArhamDataChangeListener) {
        arhamDataChangeListeners.update { current -> current - listener }
    }

    /** إطلاق المستمعين بعد COMMIT — فشل مستمعٍ لا يُفسد الكتابة ولا يسقط بقية المستمعين. */
    private fun notifyArhamDataChanged() {
        // لقطةٌ ذرّية لقائمةٍ ثابتة ثم الإطلاق خارجها (P3-1): تسجيل/إلغاء أثناء الإشعار
        // لا يرمي ConcurrentModificationException ولا يحتجز الكاتب.
        arhamDataChangeListeners.value.forEach { listener ->
            runCatching { listener.onArhamDataChanged() }
        }
    }

    /**
     * خطاف حقن فشل داخل معاملة [applySyncBatch] لاختبار التراجع الذرّي — يُستدعى بعد
     * كتابة الدفعة وقبل `COMMIT`؛ الاستثناء الرمي يُرجِع المعاملة كاملة.
     */
    internal var syncBatchFaultHook: (() -> Throwable)? = null

    /** خطاف حقن فشل داخل معاملة [cleanupExpiredData] — بنفس مبدأ [syncBatchFaultHook]. */
    internal var cleanupFaultHook: (() -> Throwable)? = null

    /**
     * خطاف حقن فشل حتمي داخل معاملة [upsertCallEvent] (T8-03) — يُرمى **بين العمليتين**
     * (بعد كتابة صف الحدث وقبل تحديث آخر التواصل المؤهَّل) ليُثبت الاختبار أن انقطاع
     * منتصف العملية المركّبة يُرجِع المعاملة كلها (قيود F-2 من مراجعة T8-01)؛ بنفس
     * مبدأ [cleanupFaultHook].
     */
    internal var callEventWriteFaultHook: (() -> Throwable)? = null

    /**
     * خطاف حقن فشل حتمي داخل معاملة [applyTopFiveMaterialization] (T8-04) — يُرمى بعد
     * كتابة الدفعة وقبل `COMMIT` (سبق [syncBatchFaultHook]) ليُثبت الاختبار أن انقطاع
     * منتصف الدفعة يُرجِعها كلها ولا يُرفع الخاد (§F.8/§F.11-د).
     */
    internal var materializationFaultHook: (() -> Throwable)? = null

    /** نسخة واقية بانتظار الحذف بعد اعتماد موثق (أول عملية Room ناجحة) — `null` = لا شيء. */
    private var pendingBackupDeletion: String? = null

    private var legacyAdopter: LegacyDatabaseAdopter? = null

    /** هل لا تزال هناك نسخة واقية معلَّقة بانتظار الحذف؟ (للاختبارات). */
    internal val hasPendingBackupDeletion: Boolean
        get() = pendingBackupDeletion != null

    /**
     * قاعدة Room الفعلية — تُبنى مرة واحدة: بعد الاعتماد الآمن للمسارات الملفية، أو
     * في الذاكرة لحاضنة الاختبارات. الفتح نفسه (إنشاء الجداول/التحقق/تسجيل
     * `user_version = SILA_ROOM_VERSION`) يتم بترف عند أول عملية.
     */
    val room: SilaRoomDatabase = buildRoomDatabase()

    private val contactEntityDao = room.contactEntityDao()
    private val arhamEntityDao = room.arhamEntityDao()
    private val serviceEntityDao = room.serviceEntityDao()
    private val callEventEntityDao = room.callEventEntityDao()
private val emergencySessionEntityDao = room.emergencySessionEntityDao()
    private val arhamInboxEntityDao = room.arhamInboxEntityDao()
    private val disputeLogEntityDao = room.disputeLogEntityDao()
    private val contactsSyncCheckpointEntityDao = room.contactsSyncCheckpointEntityDao()

    private fun buildRoomDatabase(): SilaRoomDatabase {
        // مصنع صريح — بلا انعكاس على iOS (النمط الرسمي لـ Room KMP).
        val factory = { SilaRoomDatabaseConstructor.initialize() }
        // جدول FTS4 المستقل يُنشأ عبر Callback — ليس كياناً مُداراً من Room (KSP على
        // أهداف Native لا يعالج كيانات @Fts4 المستقلة — خلل Room 3.0.3، ADR-0011 §FTS4).
        // onOpen مع IF NOT EXISTS: غير مُعيد وآمن على كل المسارات (تثبيت جديد، اعتماد
        // قاعدة قديمة بعد الشيم، فتحات لاحقة) — بلا مشغّلات، المزامنة يدوية حصراً.
        val ftsCallback = object : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                adoptionStageHook?.invoke(AdoptionVerificationStage.ROOM_OPEN)
                connection.executeSQL(LegacyFtsDdl.CREATE_CONTACTS_FTS)
            }
        }
        return if (dbFilePath == ":memory:") {
            Room.inMemoryDatabaseBuilder<SilaRoomDatabase>(factory = factory)
                .setDriver(BundledSQLiteDriver())
                // سياق تنفيذ الاستعلامات = مُوزِّع io الخاص بالتطبيق (احتمالية الحتمية في
                // الاختبارات: Unconfined يمرر التنفيذ تزامنياً — بلا خيوط حقيقية تخفيها
                // runTest الافتراضية). بلا هذا، يفترض Room افتراضياً Dispatchers.IO حقيقياً.
                .setQueryCoroutineContext(dispatchers.io)
                .addMigrations(*SILA_MIGRATIONS)
                .addCallback(ftsCallback)
                .build()
        } else {
            // الاعتماد الآمن قبل فتح Room — فشل آمن على أي مخطط غير مصنَّف.
            val adopter = LegacyDatabaseAdopter(dbFilePath).also {
                it.backupDeleteFaultHook = backupDeleteOverride
            }
            val adoption = runBlocking { adopter.adoptIfNeeded() }
            legacyAdopter = adopter
            pendingBackupDeletion = adoption.backupPath
            Room.databaseBuilder<SilaRoomDatabase>(dbFilePath, factory)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(dispatchers.io)
                // ترحيل v1→v2 (جداول الحقول القياسية — T6/Issue #27). مسار الاعتماد
                // (user_version = 0) يسبق هذا ويُنشئ المخطط النهائياً مباشرةً — ADR-0011.
                .addMigrations(*SILA_MIGRATIONS)
                .addCallback(ftsCallback)
                .build()
        }
    }

    private val contactsState: MutableStateFlow<List<Contact>>
    private val servicesState: MutableStateFlow<List<Service>>

    init {
        try {
            contactsState = MutableStateFlow(runBlocking { loadOrSeedContactsBlocking() })
            servicesState = MutableStateFlow(runBlocking { loadOrSeedServicesBlocking() })
            runBlocking { deleteBackupAfterVerifiedAdoption() }
        } catch (t: Throwable) {
            runCatching { room.close() }
            throw t
        }
    }

    // ===== البنية التحتية للكتابة المركّبة (SQL خام حرفي داخل معاملة Room) =====

    /** تنفيذ [block] داخل معاملة كتابة IMMEDIATE على اتصال الكاتب الوحيد لـ Room. */
    private suspend fun <R> inWriteTransaction(block: suspend (Transactor) -> R): R =
        room.useWriterConnection { transactor ->
            transactor.immediateTransaction { block(transactor) }
        }

    /**
     * حذف النسخة الواقية بعد اكتمال القراءات الأولية والتحقق من سلامة القاعدة الحيّة (SR-04).
     * لا تُحذف النسخة إلا بعد نجاح: فتح Room، التحقق من المخطط، `user_version` =
     * [SILA_ROOM_VERSION]، `PRAGMA integrity_check == "ok"`، قابلية قراءة `contacts_fts`،
     * وقراءات DAOs الأربعة.
     */
    private suspend fun deleteBackupAfterVerifiedAdoption() {
        if (dbFilePath == ":memory:") return
        val backupPath = pendingBackupDeletion ?: return

        verifyLiveDatabaseForAdoption()

        adoptionStageHook?.invoke(AdoptionVerificationStage.BEFORE_BACKUP_DELETE)
        adoptionStageHook?.invoke(AdoptionVerificationStage.BACKUP_DELETE)

        val deleted = legacyAdopter?.deleteBackupAfterVerifiedAdoption()
            ?: runCatching { SilaDatabaseBackup.deleteBackupIfExists(dbFilePath) }.getOrDefault(false)
        if (deleted && !PlatformFileOps.fileExists(backupPath)) {
            pendingBackupDeletion = null
        }
    }

    private suspend fun verifyLiveDatabaseForAdoption() {
        room.useReaderConnection { transactor ->
            adoptionStageHook?.invoke(AdoptionVerificationStage.VERSION_CHECK)
            transactor.usePrepared("PRAGMA user_version") { stmt ->
                val version = if (stmt.step()) stmt.getLong(0).toInt() else -1
                // الإصدار المتوقع = إصدار Room الحالي ([SILA_ROOM_VERSION]) — Room يختم
                // إصداره على القاعدة الحية بعد الافتتاح/الترحيل (T6: v2 بعد MIGRATION_1_2).
                if (version != SILA_ROOM_VERSION) {
                    throw LegacyAdoptionException(
                        "PR_B adoption verification: expected user_version=$SILA_ROOM_VERSION, actual=$version",
                    )
                }
            }

            adoptionStageHook?.invoke(AdoptionVerificationStage.INTEGRITY_CHECK)
            transactor.usePrepared("PRAGMA integrity_check") { stmt ->
                var rowCount = 0
                var allOk = true
                while (stmt.step()) {
                    rowCount++
                    if (stmt.isNull(0) || stmt.getText(0) != "ok") {
                        allOk = false
                    }
                }
                if (rowCount == 0 || !allOk) {
                    throw LegacyAdoptionException(
                        "PR_B adoption verification: live database failed PRAGMA integrity_check",
                    )
                }
            }

            adoptionStageHook?.invoke(AdoptionVerificationStage.FTS_CHECK)
            transactor.usePrepared("SELECT count(*) FROM contacts_fts") { stmt ->
                val count = if (stmt.step()) stmt.getLong(0) else -1L
                if (count < 0L) {
                    throw LegacyAdoptionException(
                        "PR_B adoption verification: contacts_fts count check failed",
                    )
                }
            }
        }
    }

    // ===== التحميل/البذر (F-01 — بوابة صريحة وجداول فارغة حصراً) =====

    private suspend fun loadOrSeedContactsBlocking(): List<Contact> {
        val loaded = loadContactsFromRoom()
        if (loaded.isNotEmpty()) return loaded

        // F-01: بلا بذر في الإنتاج — قاعدة فارغة تبقى فارغة (الاختبارات تُبذِّر صراحةً).
        if (!seedDemoData) return emptyList()

        val initial = seedInitialContacts()
        inWriteTransaction { conn ->
            initial.forEach { writeContactToSqlite(conn, it) }
        }
        return initial
    }

    private suspend fun loadOrSeedServicesBlocking(): List<Service> {
        adoptionStageHook?.invoke(AdoptionVerificationStage.SERVICES_READ)
        val loaded = serviceEntityDao.getAll().map { it.toDomain() }
        if (loaded.isNotEmpty()) return loaded

        // F-01: بلا بذر في الإنتاج — قاعدة فارغة تبقى فارغة (الاختبارات تُبذِّر صراحةً).
        if (!seedDemoData) return emptyList()

        val initial = seedInitialServices()
        serviceEntityDao.upsertAll(initial.map { it.toEntity() })
        return initial
    }

    private suspend fun loadContactsFromRoom(): List<Contact> {
        adoptionStageHook?.invoke(AdoptionVerificationStage.CONTACTS_READ)
        val contactEntities = contactEntityDao.getActiveContacts()

        // SR-04 (§5.2): لا يجوز تخطي قراءة `contact_phones` و`arham` حتى لو كان جدول
        // `contacts` خالياً من الصفوف النشطة — التحقق الأولي يشمل الجداول الأساسية دائماً.
        adoptionStageHook?.invoke(AdoptionVerificationStage.CONTACT_PHONES_READ)
        val phonesByContact = contactEntityDao.getAllPhones()
            .groupBy { it.contactId }

        adoptionStageHook?.invoke(AdoptionVerificationStage.ARHAM_READ)
        val arhamByContact = arhamEntityDao.getAll().associateBy { it.contactId }

        if (contactEntities.isEmpty()) return emptyList()

        // أطفال T6 (v2): القراءة للكاش **كاملة** حصراً (ADR-0012) — الكاش مصدر العرض،
        // وأي جهة بلا أطفالها في الكاش ستُمحى بياناتها عند إعادة كتابتها (SSOT).
        val emailsByContact = contactEntityDao.getAllEmails().groupBy { it.contactId }
        val addressesByContact = contactEntityDao.getAllAddresses().groupBy { it.contactId }
        val datesByContact = contactEntityDao.getAllDates().groupBy { it.contactId }
        val customFieldsByContact = contactEntityDao.getAllCustomFields().groupBy { it.contactId }
        val websitesByContact = contactEntityDao.getAllWebsites().groupBy { it.contactId }

        return contactEntities.map { entity ->
            entity.toDomain(
                phones = phonesByContact[entity.id].orEmpty(),
                arham = arhamByContact[entity.id],
            ).copy(
                emails = emailsByContact[entity.id].orEmpty().map { it.toDomain() },
                addresses = addressesByContact[entity.id].orEmpty().map { it.toDomain() },
                dates = datesByContact[entity.id].orEmpty().map { it.toDomain() },
                customFields = customFieldsByContact[entity.id].orEmpty().map { it.toDomain() },
                websites = websitesByContact[entity.id].orEmpty().map { it.toDomain() },
            )
        }
    }

    // ===== ContactDao =====

    override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> {
        val normalizedQuery = ArabicTextNormalizer.normalize(searchQuery)
        val phoneQuery = PhoneNormalizer.normalizeToE164(searchQuery)

        return contactsState.map { list ->
            list.filter { contact ->
                val matchesCategory = category == null || contact.category == category
                val matchesQuery = normalizedQuery.isEmpty() ||
                    contact.normalizedName.contains(normalizedQuery) ||
                    contact.phones.any { it.e164.contains(searchQuery.trim()) || (phoneQuery.isNotEmpty() && it.e164.contains(phoneQuery)) }
                matchesCategory && matchesQuery
            }.sortedBy { it.name.displayName }
        }.flowOn(dispatchers.io)
    }

    override fun observeFavorites(): Flow<List<Contact>> =
        contactsState.map { list -> list.filter { it.isFavorite } }
            .flowOn(dispatchers.io)

    override fun observeCategoryCounts(): Flow<Map<Category, Int>> =
        contactsState.map { list ->
            Category.entries.associateWith { cat ->
                list.count { it.category == cat }
            }
        }.flowOn(dispatchers.io)

    override suspend fun getContactById(id: Long): Contact? = withContext(dispatchers.io) {
        contactsState.value.find { it.id == id }
    }

    override fun observeContactById(id: Long): Flow<Contact?> =
        contactsState.map { list -> list.find { it.id == id } }
            .flowOn(dispatchers.io)

    override suspend fun upsertContact(contact: Contact): Unit = withContext(dispatchers.io) {
        val normalized = contact.copy(
            normalizedName = ArabicTextNormalizer.normalize(contact.name.displayName)
        )
        dbMutex.withLock {
            val resolvedId = inWriteTransaction { conn -> writeContactToSqlite(conn, normalized) }
            val persisted = normalized.copy(id = resolvedId)
            contactsState.update { current ->
                val existingIndex = current.indexOfFirst { it.id == persisted.id }
                if (existingIndex >= 0) {
                    current.toMutableList().apply { set(existingIndex, persisted) }
                } else {
                    current + persisted
                }
            }
        }
    }

    /**
     * تصنيف جهة واحدة — تحديث مُوجَّه (SET category) بدل إعادة كتابة الصف كاملاً:
     * الحالة المُلاحَظة نفسها (لا يُلمس updatedAt ولا الهواتف ولا أرحام ولا FTS).
     */
    override suspend fun updateCategory(contactId: Long, category: Category): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            var updatedContact: Contact? = null
            contactsState.update { current ->
                current.map {
                    if (it.id == contactId) {
                        it.copy(category = category).also { updated -> updatedContact = updated }
                    } else {
                        it
                    }
                }
            }
            updatedContact?.let { contactEntityDao.updateCategories(listOf(contactId), category) }
        }
    }

    override suspend fun deleteContact(contactId: Long): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            inWriteTransaction { conn -> deleteContactFromSqlite(conn, contactId) }
            contactsState.update { current -> current.filterNot { it.id == contactId } }
        }
    }

    // ===== T7: الدمج والتراجع (Issue #28 — ADR-0004/spec 04 §أ) =====

    /** خطاف حقن فشل داخل معاملة الدمج قبل COMMIT — اختبار التراجع الذرّي حصراً (نمط materializationFaultHook). */
    internal var mergeFaultHook: (() -> Throwable)? = null

    /** خطاف حقن فشل داخل معاملة الاسترجاع قبل COMMIT — اختبار idempotency القفل حصراً. */
    internal var restoreFaultHook: (() -> Throwable)? = null

    /** الأدلة المرجعية المكتشفة داخل معاملة الدمج — تُغذّى إلى باني الحمولة وتُعاد للناتج. */
    class MergeReferences(
        val callLogIds: List<String>,
        val parentLinks: List<Pair<Long, Long>>,
    )

    /** ناتج معاملة الدمج: معرّف اللقطة + نفس المراجع المسجَّلة في حمولتها. */
    class MergeCommitResult(val snapshotId: Long, val references: MergeReferences)

    /**
     * دمج Room كامل داخل **معاملة واحدة** (عهد الواجهة — الكاش بعد COMMIT حصراً):
     * 1. اكتشاف المراجع (أحداث المكالمات الممتصة + روابط القرابة المشيرة إليهم) **داخل**
     *    المعاملة — فلا سباق بين القراءة والكتابة — وبناؤ الحمولة عبر [buildPayloadJson]
     *    (JSON معتم يملكه مستهلكو الواجهة — عقد `contacts_sync_checkpoints`) بحيث تُكتب
     *    اللقطة بحمولتها الكاملة ذرّياً.
     * 2. صف الناجي الكامل + أبناؤه + أرحامه (الهوية المهاجرة بعد التجميع المنصوي داخل
     *    [mergedContact] — agy F2c) عبر [writeContactToSqlite] (upsert بمعرّف).
     * 3. إعادة توجيه الأحداث والروابط بالمراجع المكتشفة نفسها.
     * 4. قبور دمج الممتصين: `systemDeletedAt` (القبر يُخفي من القائمة والفهرس، ويُحفظ الصفُّ
     *    والأبناء للاسترجاع الحرفي) + إسقاط صفوف FTS (القبور لا تُفهرس).
     * يعيد معرّف اللقطة (مفتاح التراجع خلال الجلسة) والمراجع المسجَّلة.
     */
    suspend fun mergeContactsInRoom(
        survivorId: Long,
        mergedContact: Contact,
        absorbedIds: List<Long>,
        snapshotGroupId: String,
        snapshotExpiresAt: Long,
        nowMillis: Long,
        buildPayloadJson: (MergeReferences) -> String,
    ): MergeCommitResult = withContext(dispatchers.io) {
        dbMutex.withLock {
            val result = inWriteTransaction { conn ->
                val callLogIds = mutableListOf<String>()
                val parentLinks = mutableListOf<Pair<Long, Long>>()
                for (absorbedId in absorbedIds) {
                    conn.usePrepared("SELECT callLogId FROM call_events WHERE contactId = ?") { stmt ->
                        stmt.bindLong(1, absorbedId)
                        while (stmt.step()) callLogIds += stmt.getText(0)
                    }
                }
                for (absorbedId in absorbedIds) {
                    conn.usePrepared("SELECT contactId, parentContactId FROM arham WHERE parentContactId = ?") { stmt ->
                        stmt.bindLong(1, absorbedId)
                        while (stmt.step()) parentLinks += stmt.getLong(0)!! to (stmt.getLong(1) ?: 0L)
                    }
                }
                val references = MergeReferences(callLogIds, parentLinks)

                conn.usePrepared(
                    """
                    INSERT INTO contact_merge_snapshots (groupId, payloadJson, contactCount, createdAt, status, restoredAt, expiresAt)
                    VALUES (?, ?, ?, ?, 'ACTIVE', NULL, ?)
                    """.trimIndent()
                ) { stmt ->
                    stmt.bindText(1, snapshotGroupId)
                    stmt.bindText(2, buildPayloadJson(references))
                    stmt.bindLong(3, (absorbedIds.size + 1).toLong())
                    stmt.bindLong(4, nowMillis)
                    stmt.bindLong(5, snapshotExpiresAt)
                    stmt.step()
                }
                var snapshotId = 0L
                conn.usePrepared("SELECT last_insert_rowid()") { stmt ->
                    stmt.step()
                    snapshotId = stmt.getLong(0)
                }

                writeContactToSqlite(conn, mergedContact)

                for (callLogId in references.callLogIds) {
                    conn.usePrepared("UPDATE call_events SET contactId = ? WHERE callLogId = ?") { stmt ->
                        stmt.bindLong(1, survivorId)
                        stmt.bindText(2, callLogId)
                        stmt.step()
                    }
                }
                // روابط القرابة التي كانت تشير إلى ممتصٍ صارت تشير إلى الناجي
                for (absorbedId in absorbedIds) {
                    conn.usePrepared("UPDATE arham SET parentContactId = ? WHERE parentContactId = ?") { stmt ->
                        stmt.bindLong(1, survivorId)
                        stmt.bindLong(2, absorbedId)
                        stmt.step()
                    }
                }
                for (absorbedId in absorbedIds) {
                    conn.usePrepared("UPDATE contacts SET systemDeletedAt = ? WHERE id = ?") { stmt ->
                        stmt.bindLong(1, nowMillis)
                        stmt.bindLong(2, absorbedId)
                        stmt.step()
                    }
                    conn.usePrepared("DELETE FROM contacts_fts WHERE docid = ?") { stmt ->
                        stmt.bindLong(1, absorbedId)
                        stmt.step()
                    }
                }
                mergeFaultHook?.invoke()?.let { throw it }
                MergeCommitResult(snapshotId, references)
            }
            contactsState.update { current ->
                val withoutAbsorbed = current.filterNot { it.id in absorbedIds.toSet() }
                withoutAbsorbed.map { if (it.id == survivorId) mergedContact else it }
            }
            result
        }
    }

    /**
     * استرجاع ما-قبل-الدمج من لقطة نشطة — **قفل لمرة-واحدة**: الصف ACTIVE فقط يُسترجع
     * (فليب RESTORED داخل المعاملة نفسها)؛ النداء الثاني يُرفض `false` بلا أي كتابة.
     * الحمولة تُفكّ داخل المعاملة عبر [decodePayload] (فكّ معتم يملكه مستهلكو الواجهة —
     * عقد `contacts_sync_checkpoints` نفسه) فتبقى الذرية كاملة: استرجاع الأعضاء بأصفارهم
     * الأصلية (إحياء القبور ثم إعادة كتابة كل صف بمعرّفه عبر [writeContactToSqlite]) +
     * إعادة توجيه أحداث المكالمات وروابط القرابة بالاتجاه المعاكس + إعادة فهرسة FTS.
     */
    suspend fun <P> undoMergeFromSnapshot(
        snapshotId: Long,
        nowMillis: Long,
        decodePayload: (String) -> P,
        absorbedIdsOf: (P) -> List<Long>,
        membersOf: (P) -> List<Contact>,
        callEventRepointsOf: (P) -> List<Pair<String, Long>>,
        parentLinkRepointsOf: (P) -> List<Pair<Long, Long>>,
    ): Boolean = withContext(dispatchers.io) {
        dbMutex.withLock {
            val restored = inWriteTransaction { conn ->
                var status: String? = null
                var payload: String? = null
                conn.usePrepared("SELECT status, payloadJson FROM contact_merge_snapshots WHERE id = ?") { stmt ->
                    stmt.bindLong(1, snapshotId)
                    if (stmt.step()) {
                        status = stmt.getText(0)
                        payload = stmt.getText(1)
                    }
                }
                if (status != ContactMergeSnapshotEntity.STATUS_ACTIVE || payload == null) {
                    return@inWriteTransaction false
                }
                conn.usePrepared(
                    "UPDATE contact_merge_snapshots SET status = 'RESTORED', restoredAt = ? WHERE id = ?"
                ) { stmt ->
                    stmt.bindLong(1, nowMillis)
                    stmt.bindLong(2, snapshotId)
                    stmt.step()
                }

                val decoded = decodePayload(payload!!)
                val members = membersOf(decoded)
                val absorbed = absorbedIdsOf(decoded)

                for ((callLogId, oldContactId) in callEventRepointsOf(decoded)) {
                    conn.usePrepared("UPDATE call_events SET contactId = ? WHERE callLogId = ?") { stmt ->
                        stmt.bindLong(1, oldContactId)
                        stmt.bindText(2, callLogId)
                        stmt.step()
                    }
                }
                for ((childContactId, oldParentId) in parentLinkRepointsOf(decoded)) {
                    conn.usePrepared("UPDATE arham SET parentContactId = ? WHERE contactId = ?") { stmt ->
                        stmt.bindLong(1, oldParentId)
                        stmt.bindLong(2, childContactId)
                        stmt.step()
                    }
                }
                // إحياء الممتصين (قبور الدمج) قبل إعادة كتابتهم بأصفارهم — writeContactToSqlite
                // يكتب systemDeletedAt = NULL حرفاً (النطاق بلا قبر عمداً) فيُحيي الصف وإلا أبناءه.
                for (absorbedId in absorbed) {
                    conn.usePrepared("UPDATE contacts SET systemDeletedAt = NULL WHERE id = ?") { stmt ->
                        stmt.bindLong(1, absorbedId)
                        stmt.step()
                    }
                }
                for (member in members) {
                    writeContactToSqlite(conn, member)
                }
                restoreFaultHook?.invoke()?.let { throw it }
                true
            }
            if (restored) {
                // الكاش بعد COMMIT حصراً: إعادة القراءة الكاملة (الاسترجاع يمسّ صفاً وصفوفاً متعددة)
                contactsState.value = loadContactsFromRoom()
            }
            restored
        }
    }

    // ——— استبعاد أزواج المكررات (duplicate_dismissals — T7) ———

    private val duplicateDismissalKeysState = MutableStateFlow<Set<String>>(emptySet())

    /** مفاتيح الأزواج/الثنائيات المرفوضة («ليست مكررات») — تُبنى منها حواف الكشف المستبعدة. */
    fun observeDuplicateDismissalKeys(): Flow<Set<String>> {
        return duplicateDismissalKeysState
    }

    /** تحميل مفتاح الاستبعاد من القرص (استدعاء تهيئة واحد — نفس انضباط كاش جهات الاتصال). */
    suspend fun loadDuplicateDismissalKeys(): Set<String> = withContext(dispatchers.io) {
        val keys = mutableSetOf<String>()
        room.useReaderConnection { reader ->
            reader.usePrepared("SELECT groupKey FROM duplicate_dismissals") { stmt ->
                while (stmt.step()) keys += stmt.getText(0)
            }
        }
        duplicateDismissalKeysState.value = keys
        keys
    }

    /** تسجيل «ليست مكررات» لثنائية — idempotent (`WHERE NOT EXISTS`). */
    suspend fun dismissDuplicatePair(groupKey: String, reason: String, nowMillis: Long): Unit = withContext(dispatchers.io) {
        inWriteTransaction { conn ->
            conn.usePrepared(
                "INSERT INTO duplicate_dismissals (groupKey, reason, createdAt) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM duplicate_dismissals WHERE groupKey = ?)"
            ) { stmt ->
                stmt.bindText(1, groupKey)
                stmt.bindText(2, reason)
                stmt.bindLong(3, nowMillis)
                stmt.bindText(4, groupKey)
                stmt.step()
            }
        }
        duplicateDismissalKeysState.update { it + groupKey }
    }

    // ——— التعلّم المحلي للفلترة الذكية (smart_filter_feedback — T7) ———

    private val smartFilterFeedbackState = MutableStateFlow<List<SmartFilterFeedbackEntity>>(emptyList())

    fun observeSmartFilterFeedback(): Flow<List<SmartFilterFeedbackEntity>> = smartFilterFeedbackState

    suspend fun loadSmartFilterFeedback(): List<SmartFilterFeedbackEntity> = withContext(dispatchers.io) {
        val rows = mutableListOf<SmartFilterFeedbackEntity>()
        room.useReaderConnection { reader ->
            reader.usePrepared("SELECT token, suggestedCategory, acceptedCount, rejectedCount FROM smart_filter_feedback") { stmt ->
                while (stmt.step()) {
                    rows += SmartFilterFeedbackEntity(
                        token = stmt.getText(0),
                        suggestedCategory = stmt.getText(1),
                        acceptedCount = stmt.getLong(2)!!.toInt(),
                        rejectedCount = stmt.getLong(3)!!.toInt(),
                    )
                }
            }
        }
        smartFilterFeedbackState.value = rows
        rows
    }

    /** قبول/رفض (token→category): upsert عدّادين داخل معاملة — «الرفع يزيد والرفض يقلل الوزن» (spec 04 §ب). */
    suspend fun recordSmartFilterFeedback(token: String, suggestedCategory: String, accepted: Boolean): Unit = withContext(dispatchers.io) {
        inWriteTransaction { conn ->
            conn.usePrepared(
                """
                INSERT INTO smart_filter_feedback (token, suggestedCategory, acceptedCount, rejectedCount)
                VALUES (?, ?, ?, 0)
                ON CONFLICT(token, suggestedCategory) DO UPDATE SET
                    acceptedCount = acceptedCount + excluded.acceptedCount
                """.trimIndent()
            ) { stmt ->
                stmt.bindText(1, token)
                stmt.bindText(2, suggestedCategory)
                stmt.bindLong(3, if (accepted) 1L else 0L)
                stmt.step()
            }
            if (!accepted) {
                conn.usePrepared(
                    "UPDATE smart_filter_feedback SET rejectedCount = rejectedCount + 1 WHERE token = ? AND suggestedCategory = ?"
                ) { stmt ->
                    stmt.bindText(1, token)
                    stmt.bindText(2, suggestedCategory)
                    stmt.step()
                }
            }
        }
        loadSmartFilterFeedback()
    }

    // ——— اللقطات: مسح ما بعد الجلسة (منسّق sharedLogic يستهلكه مع الكاتب المنصوي) ———

    /** اللقطة النشطة بالمعرّف (للعرض والتراجع) — بلا قبور. */
    suspend fun mergeSnapshotById(snapshotId: Long): ContactMergeSnapshotEntity? = withContext(dispatchers.io) {
        var row: ContactMergeSnapshotEntity? = null
        room.useReaderConnection { reader ->
            reader.usePrepared(
                "SELECT id, groupId, payloadJson, contactCount, createdAt, status, restoredAt, expiresAt FROM contact_merge_snapshots WHERE id = ?"
            ) { stmt ->
                stmt.bindLong(1, snapshotId)
                if (stmt.step()) {
                    row = ContactMergeSnapshotEntity(
                        id = stmt.getLong(0)!!,
                        groupId = stmt.getText(1),
                        payloadJson = stmt.getText(2),
                        contactCount = stmt.getLong(3)!!.toInt(),
                        createdAt = stmt.getLong(4)!!,
                        status = stmt.getText(5),
                        restoredAt = if (stmt.isNull(6)) null else stmt.getLong(6),
                        expiresAt = stmt.getLong(7)!!,
                    )
                }
            }
        }
        row
    }

    /** اللقطات النشطة التي انقضت جلستها (expiresAt/createdAt أقدم من الحد) — مرشّحة للمسح المؤجل. */
    suspend fun activeMergeSnapshotsForSweep(olderThan: Long): List<ContactMergeSnapshotEntity> = withContext(dispatchers.io) {
        val rows = mutableListOf<ContactMergeSnapshotEntity>()
        room.useReaderConnection { reader ->
            reader.usePrepared(
                "SELECT id, groupId, payloadJson, contactCount, createdAt, status, restoredAt, expiresAt FROM contact_merge_snapshots WHERE status = 'ACTIVE' AND createdAt <= ?"
            ) { stmt ->
                stmt.bindLong(1, olderThan)
                while (stmt.step()) {
                    rows += ContactMergeSnapshotEntity(
                        id = stmt.getLong(0)!!,
                        groupId = stmt.getText(1),
                        payloadJson = stmt.getText(2),
                        contactCount = stmt.getLong(3)!!.toInt(),
                        createdAt = stmt.getLong(4)!!,
                        status = stmt.getText(5),
                        restoredAt = if (stmt.isNull(6)) null else stmt.getLong(6),
                        expiresAt = stmt.getLong(7)!!,
                    )
                }
            }
        }
        rows
    }

    /** ختم لقطة EXPIRED بعد اكتمال مسحها المنصوي — داخل معاملة الكاتب. */
    suspend fun markMergeSnapshotExpired(snapshotId: Long): Unit = withContext(dispatchers.io) {
        inWriteTransaction { conn ->
            conn.usePrepared("UPDATE contact_merge_snapshots SET status = 'EXPIRED' WHERE id = ?") { stmt ->
                stmt.bindLong(1, snapshotId)
                stmt.step()
            }
        }
    }

    /** حذف صفوف اللقطات المنقضية (RESTORED/EXPIRED) — ركوب دورة الاحتفاظ القائمة (agy F1). */
    suspend fun purgeMergeSnapshotsOlderThan(olderThan: Long): Unit = withContext(dispatchers.io) {
        inWriteTransaction { conn ->
            conn.usePrepared(
                "DELETE FROM contact_merge_snapshots WHERE status != 'ACTIVE' AND createdAt <= ?"
            ) { stmt ->
                stmt.bindLong(1, olderThan)
                stmt.step()
            }
        }
    }


    // ===== البحث والعمليات الجماعية (Issue #24 · T3) =====

    override fun searchContacts(query: String): Flow<List<Contact>> {
        val normalizedQuery = ArabicTextNormalizer.normalize(query).trim()
        val digitsQuery = if (normalizedQuery.isEmpty()) {
            ""
        } else {
            normalizedQuery.filter(Char::isDigit)
        }

        return contactsState.map { list ->
            if (normalizedQuery.isEmpty()) {
                // عقد الاستعلام الفارغ: كل الجهات الحيّة (نفس دلالات observeContacts).
                list.sortedBy { it.name.displayName }
            } else {
                val matchedIds = resolveSearchContactIds(normalizedQuery, digitsQuery)
                list.filter { it.id in matchedIds }.sortedBy { it.name.displayName }
            }
        }.flowOn(dispatchers.io)
    }

    /**
     * معرّفوات التطابقات: FTS MATCH (معامل مُقيَّد) ∪ أجزاء الهواتف — بلا قبور.
     * القراءات عبر DAOs كيانات Room (اتصال القارئ) — كاش الذاكرة هو مصدر العرض.
     */
    private suspend fun resolveSearchContactIds(normalizedQuery: String, digitsQuery: String): Set<Long> =
        dbMutex.withLock {
            val ids = mutableSetOf<Long>()
            buildFtsMatchExpression(normalizedQuery)?.let { matchExpression ->
                ids += searchFtsDocidsRaw(matchExpression).toSet()
            }
            if (digitsQuery.isNotEmpty()) {
                ids += contactEntityDao.findContactIdsByPhoneDigits(digitsQuery)
            }
            ids
        }

    /**
     * تعبير `MATCH` مُقيَّد آمن: كل رمز للاستعلام يُنقَّى من محارف مشغّلات FTS
     * (`"`, `*`, `(`, `)`, `:`, `,`, `-`) ثم يُلوح بـ `*` (بادئ جزئي)؛ والرموز تُربط
     * بـ AND. استعلام بلا رموز (محارف خاصة حصراً) ⇒ `null` = لا تطابق FTS.
     */
    private fun buildFtsMatchExpression(normalizedQuery: String): String? {
        val tokens = normalizedQuery.split(Regex("\\s+")).mapNotNull { token ->
            token.filter(Char::isLetterOrDigit).takeIf(String::isNotEmpty)
        }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " AND ") { token -> "$token*" }
    }

    /**
     * بحث FTS عبر SQL خام على اتصال قارئ Room — الجدول غير مُدار (Callback إنشاء —
     * KDoc الفئة) لذا لا يمر عبر DAO كيانات. المعامل مُقيَّد آمن من
     * [buildFtsMatchExpression] (لا استعلام خام من المستخدم).
     */
    private suspend fun searchFtsDocidsRaw(matchExpression: String): List<Long> =
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT docid FROM contacts_fts WHERE contacts_fts MATCH ?") { stmt ->
                stmt.bindText(1, matchExpression)
                buildList {
                    while (stmt.step()) add(stmt.getLong(0))
                }
            }
        }

    /** تبديل المفضلة — تحديث مُوجَّه (نفس الحالة المُلاحَظة للسلوك التاريخي). */
    override suspend fun setFavorite(contactId: Long, isFavorite: Boolean): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            var updatedContact: Contact? = null
            contactsState.update { current ->
                current.map {
                    if (it.id == contactId) {
                        it.copy(isFavorite = isFavorite).also { updated -> updatedContact = updated }
                    } else {
                        it
                    }
                }
            }
            updatedContact?.let { contactEntityDao.setFavorite(contactId, isFavorite) }
        }
    }

    override suspend fun updateCategories(contactIds: Collection<Long>, category: Category): Unit =
        withContext(dispatchers.io) {
            val targets = contactIds.toSet()
            if (targets.isEmpty()) return@withContext
            dbMutex.withLock {
                val updated = mutableListOf<Contact>()
                contactsState.update { current ->
                    current.map {
                        if (it.id in targets) {
                            it.copy(category = category).also { updatedContact -> updated += updatedContact }
                        } else {
                            it
                        }
                    }
                }
                if (updated.isNotEmpty()) contactEntityDao.updateCategories(targets, category)
            }
        }

    override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean): Unit =
        withContext(dispatchers.io) {
            val targets = contactIds.toSet()
            if (targets.isEmpty()) return@withContext
            dbMutex.withLock {
                val updated = mutableListOf<Contact>()
                contactsState.update { current ->
                    current.map {
                        if (it.id in targets) {
                            it.copy(isFavorite = isFavorite).also { updatedContact -> updated += updatedContact }
                        } else {
                            it
                        }
                    }
                }
                if (updated.isNotEmpty()) contactEntityDao.setFavorites(targets, isFavorite)
            }
        }

    override suspend fun deleteContacts(contactIds: Collection<Long>): Unit = withContext(dispatchers.io) {
        val targets = contactIds.toSet()
        if (targets.isEmpty()) return@withContext
        dbMutex.withLock {
            inWriteTransaction { conn ->
                targets.forEach { targetId -> deleteContactFromSqlite(conn, targetId) }
            }
            contactsState.update { current -> current.filterNot { it.id in targets } }
        }
    }

    override suspend fun recordInAppCallAttempt(
        callLogId: String,
        e164: String,
        timestampMillis: Long,
    ): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            // T8-02: الكاتب الإرثي (T6) يُصدر صف v3 بنفس محتوى معلوماته v2 حرفاً،
            // معبَّراً بقواعد §H.2 نفسها (مصدر مفرد للدليل، الجديد كله NULL، الدURATION
            // الصفر كما هي) — بلا اختراع دليل جديد (ATTEMPTED/startedAt قادمان مع
            // مسار آلة الحالات في T8-03/T8-06 الذي يستبدل هذا المسار).
            callEventEntityDao.upsert(
                CallEventEntity(
                    callLogId = callLogId,
                    e164 = e164,
                    timestamp = timestampMillis,
                    primarySource = CallSource.IN_APP,
                    evidenceSources = setOf(CallSource.IN_APP),
                    remoteNumber = null,
                    contactId = null,
                    direction = CallDirection.OUT,
                    startedAt = null,
                    connectedAt = null,
                    endedAt = null,
                    durationSec = 0,
                    outcome = CallOutcome.UNKNOWN,
                    association = CallAssociation.UNASSOCIATED,
                    confirmation = CallConfirmation.NOT_REQUIRED,
                    confidence = CallConfidence.LOW,
                    dedupeKey = null,
                    simSlot = null,
                )
            )
        }
    }

    override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction> =
        withContext(dispatchers.io) {
            if (e164s.isEmpty() || limit <= 0) return@withContext emptyList()
            // T8-02: صفوف اتجاهها NULL (إرث MISSED المهاجر) لا يمكن عرضها في عقد T6
            // (اتجاه غير فارغ) — تُتخطى بلا اختلاق قيمة اتجاه (ADR-0006: لا يُستنتج).
            // مسار أرحام الجديد (T8-03) يقرأ CallEvent الكاملة مباشرة.
            callEventEntityDao.getRecentForE164s(e164s, limit).mapNotNull { event ->
                event.direction?.let { direction ->
                    RecentInteraction(
                        callLogId = event.callLogId,
                        e164 = event.e164,
                        timestampMillis = event.timestamp,
                        direction = direction,
                        durationSec = event.durationSec ?: 0,
                        source = event.primarySource,
                    )
                }
            }
        }

    override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> =
        withContext(dispatchers.io) {
            contactsState.value.filter { contact ->
                contact.id != excludeContactId && contact.phones.any { it.e164 == e164 }
            }
        }

    // ===== ArhamDao =====

    override fun observeArhamContacts(): Flow<List<Contact>> =
        contactsState.map { list ->
            list.filter { it.category == Category.FAMILY && it.arham != null }
        }.flowOn(dispatchers.io)

    override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            var updatedContact: Contact? = null
            contactsState.update { current ->
                current.map {
                    if (it.id == contactId) {
                        it.copy(category = Category.FAMILY, arham = info).also { u -> updatedContact = u }
                    } else {
                        it
                    }
                }
            }
            updatedContact?.let { inWriteTransaction { conn -> writeContactToSqlite(conn, it) } }
            notifyArhamDataChanged() // T8-10 · D3: آخر تواصل/علاقة أرحام تغيّرت — بعد COMMIT حصراً
        }
    }

    // ===== ArhamDao — استمرارية إدخال أحداث المكالمات (T8-03 · ADR-0006 · Issue #29) =====
    // القراءات عبر DAOs كيانات Room المولَّدة؛ والكتابة المركّبة (حدث + آخر تواصل مؤهَّل)
    // SQL خام داخل معاملة IMMEDIATE واحدة حصراً — قيد F-2 من مراجعة T8-01: لا انفصام
    // بين الحدث وتحديث `lastContactedAt` المؤهَّل (فشل أي جزء يُرجِع العملية كلها).

    override suspend fun getCallEventById(eventId: String): CallEvent? = withContext(dispatchers.io) {
        callEventEntityDao.getById(eventId)?.toCallEvent()
    }

    override suspend fun findCallEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
        withContext(dispatchers.io) {
            callEventEntityDao.findBetween(fromMillis, toMillis).map { it.toCallEvent() }
        }

    /** أحداث `PENDING` حصراً (T8-06 · AC-7) — استعلام Room مباشر، قراءة بلا كتابة. */
    override fun observePendingCallEvents(): Flow<List<CallEvent>> =
        callEventEntityDao.observePending().map { entities -> entities.map { it.toCallEvent() } }
            .flowOn(dispatchers.io)

    /** أحداث النافذة `[fromMillis, toMillis]` (T8-07 · AC-2) — استعلام Room مباشر، قراءة حصراً. */
    override fun observeCallEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> =
        callEventEntityDao.observeBetween(fromMillis = fromMillis, toMillis = toMillis)
            .map { entities -> entities.map { it.toCallEvent() } }
            .flowOn(dispatchers.io)

    override suspend fun upsertCallEvent(event: CallEvent, lastContactedUpdate: LastContactedUpdate?): Unit =
        withContext(dispatchers.io) {
            // مدقِّقا الخريطة (بلا رقم / اتجاه MISSED) يُخفقان مغلقَي الصمام قبل أي معاملة —
            // لا صف جزئي ولا قيمة مُختلقة (§G.3 UNRESOLVED مؤجل لـ T8-06 · §H.4 · F-3).
            val entity = event.toCallEventEntity()
            dbMutex.withLock {
                inWriteTransaction { conn ->
                    writeCallEventToSqlite(conn, entity)
                    // خطاف الفشل بين العمليتين حصراً: انقطاع هنا يُثبت أن الحدث وحده لا يبقى.
                    callEventWriteFaultHook?.let { hook -> throw hook() }
                    if (lastContactedUpdate != null) {
                        writeLastContactedMaxToSqlite(conn, lastContactedUpdate)
                    }
                }
                // تحديث كاش الذاكرة بعد نجاح COMMIT حصراً (عقد الكتابات المركّبة — SSOT).
                if (lastContactedUpdate != null) {
                    refreshLastContactedInCache(lastContactedUpdate)
                }
                notifyArhamDataChanged() // T8-10 · D3: حدث مكالمة/آخر تواصل تغيّرا — بعد COMMIT
            }
        }

    override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long): Unit =
        withContext(dispatchers.io) {
            dbMutex.withLock {
                // كتابة أحادية العبارة عبر DAO كيان Room — max(...) لا تنقص قيمة أحدث قائماً
                // (idempotent: استدعاء النطاق التالي للمسار المؤهَّل بلا أثر دلالي).
                arhamEntityDao.updateLastContactedMax(contactId, timestampMillis)
                refreshLastContactedInCache(LastContactedUpdate(contactId = contactId, timestampMillis = timestampMillis))
                notifyArhamDataChanged() // T8-10 · D3 — بعد نجاح الكتابة
            }
        }

    /** تحديث آخر تواصل في كاش الذاكرة بحدّه الأقصى — الصف الغائب من الأرحام لا يُنشأ. */
    private fun refreshLastContactedInCache(update: LastContactedUpdate) {
        contactsState.update { current ->
            current.map { contact ->
                if (contact.id == update.contactId && contact.arham != null) {
                    val currentTs = contact.arham?.lastContactedAt
                    val nextTs = if (currentTs == null || currentTs < update.timestampMillis) {
                        update.timestampMillis
                    } else {
                        currentTs
                    }
                    contact.copy(arham = contact.arham?.copy(lastContactedAt = nextTs))
                } else {
                    contact
                }
            }
        }
    }

    // ===== TopFiveMaterializationDao — تجسيد «أهم 5» ذرّي (T8-04 · §F · Issue #29) =====
    // القراءات من كاش الذاكرة الكامل (المرآة الحيّة المتطابقة مع Room بعد كل COMMIT —
    // القبور تُزال من الكاش عند التعليم)، والكتابة SQL خام مُوجَّه داخل معاملة IMMEDIATE
    // واحدة حصراً (§F.8/§F.9): `category = FAMILY` مُوجَّهاً بلا أعمدة أخرى، وصف `arham`
    // يُملأ فقط بغيابه على حقيقة القاعدة (حارس NOT EXISTS = قرار F.5 حتى بعد أي تسابق)،
    // بلا `updatedAt` ولا FTS (سبق updateCategories)، وبلا صفوف وهمية (§F.7).

    override suspend fun hasLiveContacts(): Boolean = withContext(dispatchers.io) {
        contactsState.value.isNotEmpty()
    }

    override suspend fun findContactsBySystemContactIds(systemContactIds: Set<String>): List<Contact> =
        withContext(dispatchers.io) {
            if (systemContactIds.isEmpty()) return@withContext emptyList()
            contactsState.value
                .filter { contact -> contact.systemContactId in systemContactIds }
                .sortedBy { it.id }
        }

    override suspend fun applyTopFiveMaterialization(
        updates: List<TopFiveMaterializationUpdate>,
    ): TopFiveMaterializationWriteResult = withContext(dispatchers.io) {
        // هوية واحدة قد تتكرر في الدفعة (اختيار مكرر) — أول هدف يكسب (idempotent — §F.6).
        val targets = updates
            .distinctBy { it.contactId }
            .sortedBy { it.contactId }
        if (targets.isEmpty()) {
            return@withContext TopFiveMaterializationWriteResult(materialized = 0, preservedUserOwned = 0, skippedMissing = 0)
        }
        dbMutex.withLock {
            var materialized = 0
            var preservedUserOwned = 0
            var skippedMissing = 0
            inWriteTransaction { conn ->
                targets.forEach { target ->
                    // حقيقة ما بعد التسابق: الجهة الحيّة وقت الكتابة (لا الكاش ولا اللقطة) —
                    // كتابة متزامنة (مزامنة/مستخدم) تحت نفس القفل لا تُداس (§F.8).
                    val liveCreatedAt = getLiveContactCreatedAt(conn, target.contactId)
                    if (liveCreatedAt == null) {
                        skippedMissing++
                        return@forEach
                    }
                    // التصنيف: تحديث مُوجَّه بقيمة صِلَة حصراً — سبق updateCategories
                    // (بلا updatedAt ولا FTS — §F.9).
                    conn.usePrepared("UPDATE contacts SET category = 'FAMILY' WHERE id = ?") { stmt ->
                        stmt.bindLong(1, target.contactId)
                        stmt.step()
                    }
                    val arhamRowExists = arhamRowExistsInSqlite(conn, target.contactId)
                    if (arhamRowExists) {
                        preservedUserOwned++
                    } else {
                        // الحارس على حقيقة القاعدة وقت الكتابة: صف `arham` غائب فقط يُملأ
                        // من الاختيار (§F.5 — معلومة المستخدم القائمة تكسب بعد أي تسابق).
                        upsertArhamRowToSqlite(conn, target.contactId, target.arham, liveCreatedAt)
                        materialized++
                    }
                }
                // خطاف الفشل بعد كتابة الدفعة وقبل COMMIT — التراجع يثبت الذرّية (§F.11-د).
                materializationFaultHook?.let { hook -> throw hook() }
            }
            // تحديث كاش الذاكرة بعد نجاح COMMIT حصراً — بنفس دلالة الغياب المكتوبة
            // على القرص (المسار المعني فقط؛ SSOT بلا انحراف).
            contactsState.update { current ->
                current.map { contact ->
                    val target = targets.firstOrNull { it.contactId == contact.id } ?: return@map contact
                    contact.copy(category = Category.FAMILY, arham = contact.arham ?: target.arham)
                }
            }
            TopFiveMaterializationWriteResult(
                materialized = materialized,
                preservedUserOwned = preservedUserOwned,
                skippedMissing = skippedMissing,
            )
        }
    }

    /** `createdAt` للجهة الحيّة من القاعدة وقت الكتابة (حقيقة مُتسابَقة — لا كاش)، أو `null` إن غابت/قُبرت. */
    private suspend fun getLiveContactCreatedAt(conn: Transactor, contactId: Long): Long? {
        var createdAt: Long? = null
        conn.usePrepared(
            "SELECT createdAt FROM contacts WHERE id = ? AND systemDeletedAt IS NULL LIMIT 1"
        ) { stmt ->
            stmt.bindLong(1, contactId)
            if (stmt.step()) createdAt = stmt.getLong(0)
        }
        return createdAt
    }

    /** هل يوجد صف أرحام للجهة على حقيقة القاعدة؟ (قراءة داخل المعاملة — نمط countRowsWhere). */
    private suspend fun arhamRowExistsInSqlite(conn: Transactor, contactId: Long): Boolean {
        var exists = false
        conn.usePrepared("SELECT 1 FROM arham WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            exists = stmt.step()
        }
        return exists
    }

    /**
     * كتابة صف أرحام جديد لجهة قائمة (T8-04) — أعمدة العهد نفسها كما في
     * [writeContactRelationsToSqlite] حرفاً، مع حارس `WHERE NOT EXISTS` على حقيقة
     * القاعدة (الصف القائم لا يُستبدل أبداً — §F.5)، حصراً داخل معاملة Room قائمة.
     */
    private suspend fun upsertArhamRowToSqlite(conn: Transactor, contactId: Long, arham: ArhamInfo, contactCreatedAt: Long) {
        val ae = arham.toEntity(contactId, contactCreatedAt)
        conn.usePrepared(
            """
            INSERT INTO arham (
                contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt
            ) SELECT ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM arham WHERE contactId = ?)
            """.trimIndent()
        ) { stmt ->
            stmt.bindLong(1, ae.contactId)
            stmt.bindText(2, ae.relation.name)
            stmt.bindText(3, ae.layer.name)
            ae.parentContactId?.let { stmt.bindLong(4, it) } ?: stmt.bindNull(4)
            stmt.bindText(5, ae.frequency.name)
            ae.lastContactedAt?.let { stmt.bindLong(6, it) } ?: stmt.bindNull(6)
            ae.pausedUntil?.let { stmt.bindLong(7, it) } ?: stmt.bindNull(7)
            stmt.bindLong(8, ae.createdAt)
            stmt.bindLong(9, contactId)
            stmt.step()
        }
    }

    // ===== ServiceDao =====

    override fun observeServices(professionId: String?, searchQuery: String): Flow<List<Service>> {
        val normalizedQuery = ArabicTextNormalizer.normalize(searchQuery)
        return servicesState.map { list ->
            list.filter { service ->
                val matchesProf = professionId == null || service.professionId == professionId
                val matchesSearch = normalizedQuery.isEmpty() ||
                    ArabicTextNormalizer.normalize(service.name).contains(normalizedQuery) ||
                    ArabicTextNormalizer.normalize(service.professionNameAr).contains(normalizedQuery) ||
                    service.professionNameEn.lowercase().contains(normalizedQuery)
                matchesProf && matchesSearch
            }.sortedByDescending { it.trustScore }
        }.flowOn(dispatchers.io)
    }

    override suspend fun upsertServices(services: List<Service>): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            serviceEntityDao.upsertAll(services.map { it.toEntity() })
            servicesState.update { current ->
                val byId = current.associateBy { it.cloudId }.toMutableMap()
                services.forEach { byId[it.cloudId] = it }
                byId.values.toList()
            }
        }
    }

    override suspend fun findByPhoneE164(e164: String): Service? = withContext(dispatchers.io) {
        val normalized = PhoneNormalizer.normalizeToE164(e164)
        servicesState.value.find { it.phoneE164 == normalized }
    }

    // ===== خطافات بذر الاختبارات (داخلية — مستهلكوها الاختبارات حصراً) =====

    suspend fun seedCallEventsForTest(events: List<CallEventEntity>) {
        inWriteTransaction { conn ->
            conn.usePrepared("DELETE FROM call_events") { it.step() }
            events.forEach { e ->
                conn.usePrepared(
                    """
                    INSERT INTO call_events (
                        callLogId, e164, timestamp, primarySource, evidenceSources,
                        remoteNumber, contactId, direction, startedAt, connectedAt,
                        endedAt, durationSec, outcome, association, confirmation,
                        confidence, dedupeKey, simSlot
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ) { stmt ->
                    stmt.bindText(1, e.callLogId)
                    stmt.bindText(2, e.e164)
                    stmt.bindLong(3, e.timestamp)
                    stmt.bindText(4, e.primarySource.name)
                    stmt.bindText(5, e.evidenceSources.map { it.name }.sorted().joinToString(separator = ","))
                    e.remoteNumber?.let { stmt.bindText(6, it) } ?: stmt.bindNull(6)
                    e.contactId?.let { stmt.bindLong(7, it) } ?: stmt.bindNull(7)
                    e.direction?.let { stmt.bindText(8, it.name) } ?: stmt.bindNull(8)
                    e.startedAt?.let { stmt.bindLong(9, it) } ?: stmt.bindNull(9)
                    e.connectedAt?.let { stmt.bindLong(10, it) } ?: stmt.bindNull(10)
                    e.endedAt?.let { stmt.bindLong(11, it) } ?: stmt.bindNull(11)
                    e.durationSec?.let { stmt.bindLong(12, it.toLong()) } ?: stmt.bindNull(12)
                    stmt.bindText(13, e.outcome.name)
                    stmt.bindText(14, e.association.name)
                    stmt.bindText(15, e.confirmation.name)
                    stmt.bindText(16, e.confidence.name)
                    e.dedupeKey?.let { stmt.bindText(17, it) } ?: stmt.bindNull(17)
                    e.simSlot?.let { stmt.bindLong(18, it.toLong()) } ?: stmt.bindNull(18)
                    stmt.step()
                }
            }
        }
    }

    suspend fun seedInboxForTest(messages: List<ArhamInboxEntity>) {
        inWriteTransaction { conn ->
            conn.usePrepared("DELETE FROM arham_inbox") { it.step() }
            messages.forEach { m ->
                conn.usePrepared(
                    "INSERT INTO arham_inbox (id, senderUid, senderName, recipientUid, messageText, direction, status, sentAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ) { stmt ->
                    stmt.bindLong(1, m.id)
                    stmt.bindText(2, m.senderUid)
                    stmt.bindText(3, m.senderName)
                    stmt.bindText(4, m.recipientUid)
                    stmt.bindText(5, m.messageText)
                    stmt.bindText(6, m.direction.name)
                    stmt.bindText(7, m.status.name)
                    stmt.bindLong(8, m.sentAt)
                    stmt.step()
                }
            }
        }
    }

    suspend fun seedDisputesForTest(disputes: List<DisputeLogEntity>) {
        inWriteTransaction { conn ->
            conn.usePrepared("DELETE FROM dispute_logs") { it.step() }
            disputes.forEach { d ->
                conn.usePrepared(
                    "INSERT INTO dispute_logs (id, serviceCloudId, description, status, createdAt, updatedAt, resolvedAt) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ) { stmt ->
                    stmt.bindLong(1, d.id)
                    stmt.bindText(2, d.serviceCloudId)
                    stmt.bindText(3, d.description)
                    stmt.bindText(4, d.status.name)
                    stmt.bindLong(5, d.createdAt)
                    stmt.bindLong(6, d.updatedAt)
                    d.resolvedAt?.let { stmt.bindLong(7, it) } ?: stmt.bindNull(7)
                    stmt.step()
                }
            }
        }
    }

    /** بذر جلسات طوارئ للاختبارات (D4 — T8-02): يستبدل محتوى `emergency_ringing_sessions` بالصفوف المُعطاة كاملة. */
    suspend fun seedEmergencySessionsForTest(sessions: List<EmergencyRingingSessionEntity>) {
        inWriteTransaction { conn ->
            conn.usePrepared("DELETE FROM emergency_ringing_sessions") { it.step() }
            sessions.forEach { s ->
                conn.usePrepared(
                    "INSERT INTO emergency_ringing_sessions (id, contactId, startedAt, lastAttemptAt, attempts, status) VALUES (?, ?, ?, ?, ?, ?)"
                ) { stmt ->
                    stmt.bindLong(1, s.id)
                    stmt.bindLong(2, s.contactId)
                    stmt.bindLong(3, s.startedAt)
                    stmt.bindLong(4, s.lastAttemptAt)
                    stmt.bindLong(5, s.attempts.toLong())
                    stmt.bindText(6, s.status.name)
                    stmt.step()
                }
            }
        }
    }

    /** قراءة اختبار مباشرة لصفوف جلسات الطوارئ — عبر SQL خام على قارئ Room (ترتيب id تصاعدياً). */
    internal suspend fun readEmergencySessionsForTest(): List<EmergencyRingingSessionEntity> =
        room.useReaderConnection { conn ->
            conn.usePrepared(
                "SELECT id, contactId, startedAt, lastAttemptAt, attempts, status FROM emergency_ringing_sessions ORDER BY id ASC"
            ) { stmt ->
                buildList {
                    while (stmt.step()) {
                        add(
                            EmergencyRingingSessionEntity(
                                id = stmt.getLong(0),
                                contactId = stmt.getLong(1),
                                startedAt = stmt.getLong(2),
                                lastAttemptAt = stmt.getLong(3),
                                attempts = stmt.getLong(4).toInt(),
                                status = RingingStatus.valueOf(stmt.getText(5))
                            )
                        )
                    }
                }
            }
        }

    // ===== EmergencySessionDao — جلسات رنين الطوارئ (T8-10 · D4 · §N · Issue #29) =====
    // فوق الجدول الأدنى v3 حرفياً (بلا تعديل مخطط)؛ كل الحسم مقيَّد بـ IN_PROGRESS
    // (idempotent حسب §N.8) والمحاولات ذرّية داخل معاملات IMMEDIATE.

    override suspend fun countSessionsStartedSince(cutoffMillis: Long): Int = withContext(dispatchers.io) {
        dbMutex.withLock {
            room.useReaderConnection { conn ->
                conn.usePrepared(
                    "SELECT COUNT(*) FROM emergency_ringing_sessions WHERE startedAt > ?"
                ) { stmt ->
                    stmt.bindLong(1, cutoffMillis)
                    if (stmt.step()) stmt.getLong(0).toInt() else 0
                }
            }
        }
    }

    override suspend fun findInProgressSession(): EmergencyRingingSessionEntity? = withContext(dispatchers.io) {
        dbMutex.withLock {
            room.useReaderConnection { conn ->
                conn.usePrepared(
                    "SELECT id, contactId, startedAt, lastAttemptAt, attempts, status FROM emergency_ringing_sessions " +
                        "WHERE status = 'IN_PROGRESS' ORDER BY id DESC LIMIT 1"
                ) { stmt ->
                    if (stmt.step()) {
                        EmergencyRingingSessionEntity(
                            id = stmt.getLong(0),
                            contactId = stmt.getLong(1),
                            startedAt = stmt.getLong(2),
                            lastAttemptAt = stmt.getLong(3),
                            attempts = stmt.getLong(4).toInt(),
                            status = RingingStatus.valueOf(stmt.getText(5)),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    override fun observeInProgressSession(): Flow<EmergencyRingingSessionEntity?> =
        emergencySessionEntityDao.observeInProgress().flowOn(dispatchers.io)

    override suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long =
        withContext(dispatchers.io) {
            dbMutex.withLock {
                inWriteTransaction { conn ->
                    // get-or-create ذرّي (مراجعة T8-10 · P1-2): فحص الجارية القائمة داخل معاملة
                    // الكاتب نفسها — سباق «انطلقين متوازيين» يلتحق بالصف القائم بدل إنشاء صفين
                    // IN_PROGRESS يُفسدان محاسبة 3/24 ساعة (§N.8: جارية واحدة حصراً).
                    var existingId: Long? = null
                    conn.usePrepared(
                        "SELECT id FROM emergency_ringing_sessions WHERE status = 'IN_PROGRESS' " +
                            "ORDER BY id DESC LIMIT 1"
                    ) { stmt ->
                        if (stmt.step()) existingId = stmt.getLong(0)
                    }
                    existingId?.let { return@inWriteTransaction it }

                    conn.usePrepared(
                        "INSERT INTO emergency_ringing_sessions (contactId, startedAt, lastAttemptAt, attempts, status) " +
                            "VALUES (?, ?, ?, 0, 'IN_PROGRESS')"
                    ) { stmt ->
                        stmt.bindLong(1, contactId)
                        stmt.bindLong(2, startedAtMillis)
                        stmt.bindLong(3, startedAtMillis)
                        stmt.step()
                    }
                    // المعرف التوليدي داخل المعاملة نفسها (اتصال الكاتب الوحيد — last_insert_rowid صحيح هنا حصراً).
                    conn.usePrepared("SELECT last_insert_rowid()") { stmt ->
                        if (stmt.step()) stmt.getLong(0) else 0L
                    }
                }
            }
        }

    override suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int? = withContext(dispatchers.io) {
        dbMutex.withLock {
            inWriteTransaction { conn ->
                // القراءة والحسم داخل معاملة IMMEDIATE واحدة — بلا سباق مع الحسم المتوازي (§N.8).
                var status = ""
                conn.usePrepared(
                    "SELECT status FROM emergency_ringing_sessions WHERE id = ?"
                ) { stmt ->
                    stmt.bindLong(1, sessionId)
                    if (stmt.step()) status = stmt.getText(0)
                }
                if (status != "IN_PROGRESS") return@inWriteTransaction null

                conn.usePrepared(
                    "UPDATE emergency_ringing_sessions SET attempts = attempts + 1, lastAttemptAt = ? WHERE id = ?"
                ) { stmt ->
                    stmt.bindLong(1, atMillis)
                    stmt.bindLong(2, sessionId)
                    stmt.step()
                }
                conn.usePrepared(
                    "SELECT attempts FROM emergency_ringing_sessions WHERE id = ?"
                ) { stmt ->
                    stmt.bindLong(1, sessionId)
                    if (stmt.step()) stmt.getLong(0).toInt() else null
                }
            }
        }
    }

    override suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean =
        withContext(dispatchers.io) {
            dbMutex.withLock {
                inWriteTransaction { conn ->
                    // حسم idempotent (§N.8): الجلسة المحسومة سلفاً بحالةٍ أخرى عمليةٌ ساكنة.
                    var current = ""
                    conn.usePrepared(
                        "SELECT status FROM emergency_ringing_sessions WHERE id = ?"
                    ) { stmt ->
                        stmt.bindLong(1, sessionId)
                        if (stmt.step()) current = stmt.getText(0)
                    }
                    if (current != "IN_PROGRESS") return@inWriteTransaction false

                    conn.usePrepared(
                        "UPDATE emergency_ringing_sessions SET status = ?, lastAttemptAt = ? WHERE id = ?"
                    ) { stmt ->
                        stmt.bindText(1, status.name)
                        stmt.bindLong(2, atMillis)
                        stmt.bindLong(3, sessionId)
                        stmt.step()
                    }
                    true
                }
            }
        }

    /** فحص اختبار مباشر لفهرس FTS (docid = contacts.id) — عبر SQL خام على قارئ Room. */
    internal suspend fun queryFtsContactIds(matchQuery: String): List<Long> =
        searchFtsDocidsRaw(matchQuery)

    // ===== ContactsSyncDao (Issue #23 · ADR-0010 §4/§7/§8) =====

    override suspend fun loadSyncSnapshotJson(): String? = withContext(dispatchers.io) {
        dbMutex.withLock {
            contactsSyncCheckpointEntityDao.getValue(ContactsSyncCheckpointEntity.KEY_SNAPSHOT)
        }
    }

    override suspend fun loadLastReconcileAtMillis(): Long? = withContext(dispatchers.io) {
        dbMutex.withLock {
            contactsSyncCheckpointEntityDao.getValue(ContactsSyncCheckpointEntity.KEY_LAST_FULL_RECONCILE_AT)
                ?.toLongOrNull()
        }
    }

    override suspend fun getSyncIndexRows(): List<ContactSyncIndexRow> = withContext(dispatchers.io) {
        dbMutex.withLock {
            contactEntityDao.getSyncIndexRows()
        }
    }

    override suspend fun applySyncBatch(
        upserts: List<Contact>,
        tombstones: List<String>,
        snapshotJson: String?,
        reconcileStampMillis: Long?,
        nowMillis: Long
    ): ContactsSyncBatchResult = withContext(dispatchers.io) {
        dbMutex.withLock {
            var upserted = 0
            var tombstoned = 0
            var restored = 0
            val cacheUpserts = mutableListOf<Contact>()
            val removedCacheIds = mutableListOf<Long>()

            inWriteTransaction { conn ->
                // 1) الـ upserts — merge-read-write: أعمدة المنصة تُعكس، وأعمدة صِلَة تُحفظ حرفياً
                //    (category, isFavorite, ringtone, voicemail, blocked, arham) — ADR-0010 §7.
                upserts.forEach { incoming ->
                    val normalizedIncoming = incoming.copy(
                        normalizedName = incoming.normalizedName.ifEmpty {
                            ArabicTextNormalizer.normalize(incoming.name.displayName)
                        }
                    )
                    val persisted = readPersistedContactBySystemId(conn, incoming.systemContactId)
                    val merged: Contact
                    if (persisted == null) {
                        merged = normalizedIncoming.copy(updatedAt = nowMillis)
                    } else {
                        if (persisted.systemDeletedAt != null) restored++
                        // تسميات الهواتف صِلَة-فقط (ADR-0012): تُحمل من الصف المخزَّن على
                        // أرقام الوارد حرفياً (مطابقة e164 ثم raw) — الانعكاس يعيد raw/
                        // e164/type/isPrimary من المنصة، والموضع في القائمة هو الترتيب.
                        fun Phone.withCarriedLabel(): Phone {
                            val previous = persisted.contact.phones.firstOrNull {
                                it.e164 == e164 && it.raw == raw
                            } ?: persisted.contact.phones.firstOrNull { it.e164 == e164 }
                            return if (previous?.label != null) copy(label = previous.label) else this
                        }
                        val mergedPhones = normalizedIncoming.phones.map { it.withCarriedLabel() }
                        merged = persisted.contact.copy(
                            // أعمدة المنصة المعكوسة:
                            lookupKey = normalizedIncoming.lookupKey,
                            name = normalizedIncoming.name,
                            photoUri = normalizedIncoming.photoUri,
                            phones = mergedPhones,
                            // T6 (ADR-0012): البريد والمواقع أطفال معكوسة من المنصة —
                            // تُعاد كتابتها من دفتر الهاتف في كل دلتا.
                            emails = normalizedIncoming.emails,
                            websites = normalizedIncoming.websites,
                            organization = normalizedIncoming.organization,
                            account = normalizedIncoming.account,
                            normalizedName = normalizedIncoming.normalizedName,
                            updatedAt = nowMillis
                        )
                        // T6 (إصلاح فقد بيانات كامن): `notes` **ليست** في قائمة الانعكاس —
                        // iOS بلا صلاحية الملاحظات يقرأها null، وكان الانعكاس القديم يمحو
                        // ملاحظات Room في كل دلتا. `notes` صِلَة-فقط: تُحفظ حرفياً من
                        // `persisted` (كما category/ringtone/voicemail/blocked/arham)،
                        // والعناوين/التواريخ/الحقول المخصصة أطفال صِلَة-فقط تنجو تلقائياً.
                    }
                    val resolvedId = writeContactToSqlite(conn, merged)
                    cacheUpserts += merged.copy(id = resolvedId)
                    upserted++
                }

                // 2) القبور — حذف ناعم بأختام من المنصة حصراً؛ بلا أي حذف فعلي (ADR-0010 §3/§6).
                tombstones.forEach { systemId ->
                    val persisted = readPersistedContactBySystemId(conn, systemId) ?: return@forEach
                    if (persisted.systemDeletedAt != null) return@forEach // مقبورة سلفاً (idempotent)
                    conn.usePrepared(
                        "UPDATE contacts SET systemDeletedAt = ?, updatedAt = ? WHERE id = ?"
                    ) { stmt ->
                        stmt.bindLong(1, nowMillis)
                        stmt.bindLong(2, nowMillis)
                        stmt.bindLong(3, persisted.contact.id)
                        stmt.step()
                    }
                    conn.usePrepared("DELETE FROM contacts_fts WHERE docid = ?") { stmt ->
                        stmt.bindLong(1, persisted.contact.id)
                        stmt.step()
                    }
                    removedCacheIds += persisted.contact.id
                    tombstoned++
                }

                // 3) اللقطة/الختم — داخل نفس المعاملة حصراً: لا تُقدَّم لقطة قبل تثبيت بياناتها.
                snapshotJson?.let { json ->
                    writeCheckpoint(conn, ContactsSyncCheckpointEntity.KEY_SNAPSHOT, json)
                }
                reconcileStampMillis?.let { stamp ->
                    writeCheckpoint(conn, ContactsSyncCheckpointEntity.KEY_LAST_FULL_RECONCILE_AT, stamp.toString())
                }

                syncBatchFaultHook?.let { hook -> throw hook() }
            }

            // تحديث كاش الذاكرة بعد نجاح COMMIT حصراً (SSOT — لا انحراف بين الكاش والقرص).
            contactsState.update { current ->
                val upsertIds = cacheUpserts.map { it.id }.toSet()
                val remaining = current.filterNot { it.id in removedCacheIds || it.id in upsertIds }
                if (cacheUpserts.isEmpty()) remaining else (remaining + cacheUpserts).sortedBy { it.id }
            }
            ContactsSyncBatchResult(upserted = upserted, tombstoned = tombstoned, restored = restored)
        }
    }

    override suspend fun clearSyncSnapshot(): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            contactsSyncCheckpointEntityDao.delete(ContactsSyncCheckpointEntity.KEY_SNAPSHOT)
        }
    }

    override suspend fun tombstoneContact(contactId: Long, nowMillis: Long): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            var tombstoned = false
            inWriteTransaction { conn ->
                conn.usePrepared("SELECT systemDeletedAt FROM contacts WHERE id = ?") { stmt ->
                    stmt.bindLong(1, contactId)
                    tombstoned = stmt.step() && stmt.isNull(0)
                }
                if (tombstoned) {
                    conn.usePrepared(
                        "UPDATE contacts SET systemDeletedAt = ?, updatedAt = ? WHERE id = ?"
                    ) { stmt ->
                        stmt.bindLong(1, nowMillis)
                        stmt.bindLong(2, nowMillis)
                        stmt.bindLong(3, contactId)
                        stmt.step()
                    }
                    conn.usePrepared("DELETE FROM contacts_fts WHERE docid = ?") { stmt ->
                        stmt.bindLong(1, contactId)
                        stmt.step()
                    }
                }
            }
            if (tombstoned) {
                contactsState.update { current -> current.filterNot { it.id == contactId } }
            }
        }
    }

    // ===== DataRetentionCleanupDao (F-02) =====

    override suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport = withContext(dispatchers.io) {
        dbMutex.withLock {
            val dayMillis = 86_400_000L
            val callCutoff = nowMillis - (365L * dayMillis)
            val inboxCutoff = nowMillis - (30L * dayMillis)
            val disputeCutoff = nowMillis - (90L * dayMillis)
            val tombstoneCutoff = nowMillis - (30L * dayMillis)
            // جلسات الطوارئ المنتهية (v3 — قرار D4): نافذة 24 ساعة + هامش = 48 ساعة
            // من آخر محاولة (§H من تقرير بوابة T8-02) — الجلسة الجارية (IN_PROGRESS)
            // لا تُمس — قرارها بيد منطق الاستئناف/الإجهاض حصراً.
            val emergencySessionCutoff = nowMillis - (2L * dayMillis)

            var deletedCalls = 0
            var deletedInbox = 0
            var archivedDisputes = 0
            var deletedEmergencySessions = 0
            val expiredTombstoneIds = mutableListOf<Long>()

            // F-02: كل عمليات التنظيف داخل معاملة واحدة — إما أن تُنجز كلها أو لا شيء
            // (انقطاع منتصف التنظيف لا يترك جداول ناقصة؛ الإعادة عند الإقلاع التالي
            // idempotent لأن الحدود الزمنية مطلقة لا نسبية).
            inWriteTransaction { conn ->
                deletedCalls = countRowsWhere(conn, "SELECT COUNT(*) FROM call_events WHERE timestamp < ?", callCutoff)
                conn.usePrepared("DELETE FROM call_events WHERE timestamp < ?") { stmt ->
                    stmt.bindLong(1, callCutoff)
                    stmt.step()
                }

                deletedEmergencySessions = conn.usePrepared(
                    "SELECT COUNT(*) FROM emergency_ringing_sessions WHERE lastAttemptAt < ? AND status <> ?"
                ) { stmt ->
                    stmt.bindLong(1, emergencySessionCutoff)
                    stmt.bindText(2, RingingStatus.IN_PROGRESS.name)
                    if (stmt.step()) stmt.getLong(0).toInt() else 0
                }
                conn.usePrepared(
                    "DELETE FROM emergency_ringing_sessions WHERE lastAttemptAt < ? AND status <> ?"
                ) { stmt ->
                    stmt.bindLong(1, emergencySessionCutoff)
                    stmt.bindText(2, RingingStatus.IN_PROGRESS.name)
                    stmt.step()
                }

                deletedInbox = countRowsWhere(conn, "SELECT COUNT(*) FROM arham_inbox WHERE sentAt < ?", inboxCutoff)
                conn.usePrepared("DELETE FROM arham_inbox WHERE sentAt < ?") { stmt ->
                    stmt.bindLong(1, inboxCutoff)
                    stmt.step()
                }

                archivedDisputes = countRowsWhere(
                    conn,
                    "SELECT COUNT(*) FROM dispute_logs WHERE status = '${DisputeStatus.RESOLVED.name}' AND resolvedAt IS NOT NULL AND resolvedAt < ?",
                    disputeCutoff
                )
                conn.usePrepared(
                    "UPDATE dispute_logs SET status = ?, updatedAt = ? WHERE status = ? AND resolvedAt IS NOT NULL AND resolvedAt < ?"
                ) { stmt ->
                    stmt.bindText(1, DisputeStatus.ARCHIVED.name)
                    stmt.bindLong(2, nowMillis)
                    stmt.bindText(3, DisputeStatus.RESOLVED.name)
                    stmt.bindLong(4, disputeCutoff)
                    stmt.step()
                }

                // قبور أقدم من 30 يوماً ← حذف نهائي (صف + هواتف + أرحام + FTS) — ADR-0010 §6.
                conn.usePrepared(
                    "SELECT id FROM contacts WHERE systemDeletedAt IS NOT NULL AND systemDeletedAt < ?"
                ) { stmt ->
                    stmt.bindLong(1, tombstoneCutoff)
                    while (stmt.step()) expiredTombstoneIds += stmt.getLong(0)
                }
                expiredTombstoneIds.forEach { deleteContactFromSqlite(conn, it) }

                cleanupFaultHook?.let { hook -> throw hook() }
            }

            // تحديث كاش الذاكرة بعد نجاح COMMIT حصراً (نفس عقد applySyncBatch — SSOT).
            if (expiredTombstoneIds.isNotEmpty()) {
                contactsState.update { current ->
                    current.filterNot { it.id in expiredTombstoneIds }
                }
            }

            CleanupReport(
                deletedCallEvents = deletedCalls,
                deletedInboxMessages = deletedInbox,
                archivedDisputes = archivedDisputes,
                deletedTombstonedContacts = expiredTombstoneIds.size,
                deletedEmergencySessions = deletedEmergencySessions
            )
        }
    }

    // ===== المساعدات الخام (SQL حرفي تاريخي — داخل معاملات Room حصراً) =====

    /**
     * كتابة جهة اتصال كاملة (صف + هواتف + أرحام + FTS) وإرجاع المعرف المحلول.
     * `id = 0` يعني إدراجاً بمعرّف تلقائي (يُقرأ من `last_insert_rowid()`).
     */
    private suspend fun writeContactToSqlite(conn: Transactor, contact: Contact): Long {
        val entity = contact.toContactEntity()
        if (entity.id == 0L) {
            conn.usePrepared(
                """
                INSERT INTO contacts (
                    id, systemContactId, lookupKey, accountType, accountName, displayName, fileAs,
                    prefix, firstName, middleName, lastName, suffix, nickname, phoneticName,
                    company, department, photoUri, category, isFavorite, customRingtoneUri,
                    sendToVoicemail, isBlocked, notes, linkedServiceCloudId, excludeFromSmartFilter,
                    normalizedName, createdAt, updatedAt, systemDeletedAt
                ) VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ) { stmt ->
                bindContactColumns(stmt, entity, startParam = 1)
                stmt.step()
            }
            var resolvedId = 0L
            conn.usePrepared("SELECT last_insert_rowid()") { stmt ->
                stmt.step()
                resolvedId = stmt.getLong(0)
            }
            writeContactRelationsToSqlite(conn, resolvedId, contact)
            return resolvedId
        }
        conn.usePrepared(
            """
            INSERT OR REPLACE INTO contacts (
                id, systemContactId, lookupKey, accountType, accountName, displayName, fileAs,
                prefix, firstName, middleName, lastName, suffix, nickname, phoneticName,
                company, department, photoUri, category, isFavorite, customRingtoneUri,
                sendToVoicemail, isBlocked, notes, linkedServiceCloudId, excludeFromSmartFilter,
                normalizedName, createdAt, updatedAt, systemDeletedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.bindLong(1, entity.id)
            bindContactColumns(stmt, entity, startParam = 2)
            stmt.step()
        }
        writeContactRelationsToSqlite(conn, entity.id, contact)
        return entity.id
    }

    /** ربط أعمدة جهة الاتصال بدءاً من [startParam] (بلا `id`). */
    private fun bindContactColumns(stmt: SQLiteStatement, entity: ContactEntity, startParam: Int) {
        var i = startParam
        stmt.bindText(i++, entity.systemContactId)
        entity.lookupKey?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.accountType?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.accountName?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        stmt.bindText(i++, entity.displayName)
        stmt.bindText(i++, entity.fileAs)
        entity.prefix?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.firstName?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.middleName?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.lastName?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.suffix?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.nickname?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.phoneticName?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.company?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.department?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.photoUri?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        stmt.bindText(i++, entity.category.name)
        stmt.bindLong(i++, if (entity.isFavorite) 1L else 0L)
        entity.customRingtoneUri?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        stmt.bindLong(i++, if (entity.sendToVoicemail) 1L else 0L)
        stmt.bindLong(i++, if (entity.isBlocked) 1L else 0L)
        entity.notes?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        entity.linkedServiceCloudId?.let { stmt.bindText(i++, it) } ?: stmt.bindNull(i++)
        stmt.bindLong(i++, if (entity.excludeFromSmartFilter) 1L else 0L)
        stmt.bindText(i++, entity.normalizedName)
        stmt.bindLong(i++, entity.createdAt)
        stmt.bindLong(i++, entity.updatedAt)
        entity.systemDeletedAt?.let { stmt.bindLong(i++, it) } ?: stmt.bindNull(i++)
    }

    /** الهواتف + أطفال T6 + أرحام + FTS لجهة تمت كتابة صفها بمعرّف [contactId]. */
    private suspend fun writeContactRelationsToSqlite(conn: Transactor, contactId: Long, contact: Contact) {
        conn.usePrepared("DELETE FROM contact_phones WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        // الترتيب = موضع العنصر في قائمة النطاق (مصدر sortOrder — ADR-0012).
        contact.phones.forEachIndexed { index, phone ->
            val pe = phone.toEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_phones (contactId, raw, e164, type, label, isPrimary, sortOrder) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, pe.contactId)
                stmt.bindText(2, pe.raw)
                stmt.bindText(3, pe.e164)
                stmt.bindText(4, pe.type)
                pe.label?.let { stmt.bindText(5, it) } ?: stmt.bindNull(5)
                stmt.bindLong(6, if (pe.isPrimary) 1L else 0L)
                stmt.bindLong(7, pe.sortOrder.toLong())
                stmt.step()
            }
        }

        conn.usePrepared("DELETE FROM contact_emails WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.emails.forEachIndexed { index, email ->
            val ee = email.toEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_emails (contactId, address, type, label, sortOrder) VALUES (?, ?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, ee.contactId)
                stmt.bindText(2, ee.address)
                stmt.bindText(3, ee.type)
                ee.label?.let { stmt.bindText(4, it) } ?: stmt.bindNull(4)
                stmt.bindLong(5, ee.sortOrder.toLong())
                stmt.step()
            }
        }

        conn.usePrepared("DELETE FROM contact_addresses WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.addresses.forEachIndexed { index, address ->
            val ae = address.toEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_addresses (contactId, street, city, region, postalCode, country, type, sortOrder) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, ae.contactId)
                ae.street?.let { stmt.bindText(2, it) } ?: stmt.bindNull(2)
                ae.city?.let { stmt.bindText(3, it) } ?: stmt.bindNull(3)
                ae.region?.let { stmt.bindText(4, it) } ?: stmt.bindNull(4)
                ae.postalCode?.let { stmt.bindText(5, it) } ?: stmt.bindNull(5)
                ae.country?.let { stmt.bindText(6, it) } ?: stmt.bindNull(6)
                stmt.bindText(7, ae.type)
                stmt.bindLong(8, ae.sortOrder.toLong())
                stmt.step()
            }
        }

        conn.usePrepared("DELETE FROM contact_dates WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.dates.forEachIndexed { index, date ->
            val de = date.toEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_dates (contactId, type, label, month, day, year, sortOrder) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, de.contactId)
                stmt.bindText(2, de.type)
                de.label?.let { stmt.bindText(3, it) } ?: stmt.bindNull(3)
                stmt.bindLong(4, de.month.toLong())
                stmt.bindLong(5, de.day.toLong())
                de.year?.let { stmt.bindLong(6, it.toLong()) } ?: stmt.bindNull(6)
                stmt.bindLong(7, de.sortOrder.toLong())
                stmt.step()
            }
        }

        conn.usePrepared("DELETE FROM contact_custom_fields WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.customFields.forEachIndexed { index, field ->
            val fe = field.toEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_custom_fields (contactId, label, value, sortOrder) VALUES (?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, fe.contactId)
                stmt.bindText(2, fe.label)
                stmt.bindText(3, fe.value)
                stmt.bindLong(4, fe.sortOrder.toLong())
                stmt.step()
            }
        }

        conn.usePrepared("DELETE FROM contact_websites WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.websites.forEachIndexed { index, url ->
            val we = url.toWebsiteEntity(contactId, sortOrder = index)
            conn.usePrepared(
                "INSERT INTO contact_websites (contactId, url, sortOrder) VALUES (?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, we.contactId)
                stmt.bindText(2, we.url)
                stmt.bindLong(3, we.sortOrder.toLong())
                stmt.step()
            }
        }

        val arham = contact.arham
        if (arham != null) {
            val ae = arham.toEntity(contactId, contact.createdAt)
            conn.usePrepared(
                """
                INSERT OR REPLACE INTO arham (
                    contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ) { stmt ->
                stmt.bindLong(1, ae.contactId)
                stmt.bindText(2, ae.relation.name)
                stmt.bindText(3, ae.layer.name)
                ae.parentContactId?.let { stmt.bindLong(4, it) } ?: stmt.bindNull(4)
                stmt.bindText(5, ae.frequency.name)
                ae.lastContactedAt?.let { stmt.bindLong(6, it) } ?: stmt.bindNull(6)
                ae.pausedUntil?.let { stmt.bindLong(7, it) } ?: stmt.bindNull(7)
                stmt.bindLong(8, ae.createdAt)
                stmt.step()
            }
        } else {
            conn.usePrepared("DELETE FROM arham WHERE contactId = ?") { stmt ->
                stmt.bindLong(1, contactId)
                stmt.step()
            }
        }

        syncContactFts(conn, contactId, contact)
    }

    /**
     * كتابة صف حدث مكالمة v3 (18 عموداً — نفس ترتيب أعمدة [CallEventsV3Ddl] حرفاً) —
     * داخل معاملات Room حصراً (T8-03). التحويلات النصية للأعداد بصيغة `name` ومصادر
     * الدليل مفصولة بفواصل مرتَّبة — نفس قواعد محولات `SilaTypeConverters` التي يطبقها
     * مسار DAOs المولَّدة (عهد التطابق بين المسارين).
     */
    private suspend fun writeCallEventToSqlite(conn: Transactor, entity: CallEventEntity) {
        conn.usePrepared(
            """
            INSERT OR REPLACE INTO call_events (
                callLogId, e164, timestamp, primarySource, evidenceSources, remoteNumber,
                contactId, direction, startedAt, connectedAt, endedAt, durationSec,
                outcome, association, confirmation, confidence, dedupeKey, simSlot
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ) { stmt ->
            stmt.bindText(1, entity.callLogId)
            stmt.bindText(2, entity.e164)
            stmt.bindLong(3, entity.timestamp)
            stmt.bindText(4, entity.primarySource.name)
            stmt.bindText(5, entity.evidenceSources.map { it.name }.sorted().joinToString(separator = ","))
            entity.remoteNumber?.let { stmt.bindText(6, it) } ?: stmt.bindNull(6)
            entity.contactId?.let { stmt.bindLong(7, it) } ?: stmt.bindNull(7)
            entity.direction?.let { stmt.bindText(8, it.name) } ?: stmt.bindNull(8)
            entity.startedAt?.let { stmt.bindLong(9, it) } ?: stmt.bindNull(9)
            entity.connectedAt?.let { stmt.bindLong(10, it) } ?: stmt.bindNull(10)
            entity.endedAt?.let { stmt.bindLong(11, it) } ?: stmt.bindNull(11)
            entity.durationSec?.let { stmt.bindLong(12, it.toLong()) } ?: stmt.bindNull(12)
            stmt.bindText(13, entity.outcome.name)
            stmt.bindText(14, entity.association.name)
            stmt.bindText(15, entity.confirmation.name)
            stmt.bindText(16, entity.confidence.name)
            entity.dedupeKey?.let { stmt.bindText(17, it) } ?: stmt.bindNull(17)
            entity.simSlot?.let { stmt.bindLong(18, it.toLong()) } ?: stmt.bindNull(18)
            stmt.step()
        }
    }

    /**
     * تحديث آخر تواصل بحدّه الأقصى داخل المعاملة المركّبة — العبارة نفسها حرفياً في
     * [ArhamEntityDao.updateLastContactedMax] (نمط T8-02: بيان COUNT/DELETE في
     * `cleanupExpiredData` و DAO كيان `call_events`)؛ `COALESCE` يعامل «لم يُتواصل
     * أبداً» (فارغة) كـ −∞ لأن `MAX` السكالارية في SQLite تُرجع فارغاً مع أي وسيط فارغ.
     */
    private suspend fun writeLastContactedMaxToSqlite(conn: Transactor, update: LastContactedUpdate) {
        conn.usePrepared(
            "UPDATE arham SET lastContactedAt = MAX(COALESCE(lastContactedAt, ?), ?) WHERE contactId = ?"
        ) { stmt ->
            stmt.bindLong(1, update.timestampMillis)
            stmt.bindLong(2, update.timestampMillis)
            stmt.bindLong(3, update.contactId)
            stmt.step()
        }
    }

    /**
     * صيانة فهرس FTS المستقل: حذف الصف القديم ثم إعادة الإدراج (docid = contacts.id).
     * القبور لا تمر من هنا إطلاقاً — تُحذف من الفهرس مباشرة في مسار tombstone؛
     * والاسترجاع يعيد الإدراج عبر [writeContactToSqlite].
     *
     * **توكين الفهرس:** كل الأعمدة تُخزَّن **معايَرة** عبر [ArabicTextNormalizer]
     * (توحيد الهمزات؛ إسقاط التشكيل؛ أرقام غربية) — نفس تعريب استعلام البحث؛
     * ولنلا فستطابق الاستعلامات المتعارضة النص الخام (فرق ة/ه و أ/إ/آ).
     */
    private suspend fun syncContactFts(conn: Transactor, contactId: Long, contact: Contact) {
        conn.usePrepared("DELETE FROM contacts_fts WHERE docid = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared(
            "INSERT INTO contacts_fts (docid, displayName, nickname, company, department, notes, normalizedName) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ) { stmt ->
            stmt.bindLong(1, contactId)
            stmt.bindText(2, ArabicTextNormalizer.normalize(contact.name.displayName))
            contact.name.nickname?.let { stmt.bindText(3, ArabicTextNormalizer.normalize(it)) } ?: stmt.bindNull(3)
            contact.organization?.company?.let { stmt.bindText(4, ArabicTextNormalizer.normalize(it)) } ?: stmt.bindNull(4)
            contact.organization?.department?.let { stmt.bindText(5, ArabicTextNormalizer.normalize(it)) } ?: stmt.bindNull(5)
            contact.notes?.let { stmt.bindText(6, ArabicTextNormalizer.normalize(it)) } ?: stmt.bindNull(6)
            stmt.bindText(
                7,
                contact.normalizedName.ifEmpty { ArabicTextNormalizer.normalize(contact.name.displayName) }
            )
            stmt.step()
        }
    }

    private suspend fun deleteContactFromSqlite(conn: Transactor, contactId: Long) {
        conn.usePrepared("DELETE FROM contact_phones WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        // أطفال T6 (v2) — كامل الأطفال يُمسح مع الصف (نفس دلالات حذف الهواتف).
        conn.usePrepared("DELETE FROM contact_emails WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contact_addresses WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contact_dates WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contact_custom_fields WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contact_websites WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM arham WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contacts_fts WHERE docid = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        conn.usePrepared("DELETE FROM contacts WHERE id = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
    }

    /**
     * قراءة جهة كاملة (صف + هواتف + أرحام) بمعرّف النظام — حتى المقبرة (للمزامنة).
     * `systemDeletedAt` لا يوجد في نموذج النطاق فيُعاد ضمن حامل منفصل.
     */
    private suspend fun readPersistedContactBySystemId(
        conn: PooledConnection,
        systemContactId: String,
    ): PersistedContact? {
        var entity: ContactEntity? = null
        conn.usePrepared(
            """
            SELECT id, systemContactId, lookupKey, accountType, accountName, displayName, fileAs,
                   prefix, firstName, middleName, lastName, suffix, nickname, phoneticName,
                   company, department, photoUri, category, isFavorite, customRingtoneUri,
                   sendToVoicemail, isBlocked, notes, linkedServiceCloudId, excludeFromSmartFilter,
                   normalizedName, createdAt, updatedAt, systemDeletedAt
            FROM contacts WHERE systemContactId = ?
            ORDER BY (systemDeletedAt IS NULL) DESC, id ASC
            """.trimIndent()
        ) { stmt ->
            stmt.bindText(1, systemContactId)
            if (stmt.step()) entity = stmt.readContactEntityRow()
        }
        val e = entity ?: return null
        val phones = mutableListOf<ContactPhoneRow>()
        conn.usePrepared(
            "SELECT id, contactId, raw, e164, type, label, sortOrder, isPrimary FROM contact_phones WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                phones += ContactPhoneRow(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    raw = stmt.getText(2),
                    e164 = stmt.getText(3),
                    type = stmt.getText(4),
                    label = if (stmt.isNull(5)) null else stmt.getText(5),
                    sortOrder = stmt.getLong(6).toInt(),
                    isPrimary = stmt.getLong(7) != 0L,
                )
            }
        }
        var arham: ArhamEntity? = null
        conn.usePrepared(
            """
            SELECT contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt
            FROM arham WHERE contactId = ?
            """.trimIndent()
        ) { stmt ->
            stmt.bindLong(1, e.id)
            if (stmt.step()) {
                arham = ArhamEntity(
                    contactId = e.id,
                    relation = Relation.valueOf(stmt.getText(1)),
                    layer = KinshipLayer.valueOf(stmt.getText(2)),
                    parentContactId = if (stmt.isNull(3)) null else stmt.getLong(3),
                    frequency = ArhamFrequency.valueOf(stmt.getText(4)),
                    lastContactedAt = if (stmt.isNull(5)) null else stmt.getLong(5),
                    pausedUntil = if (stmt.isNull(6)) null else stmt.getLong(6),
                    createdAt = stmt.getLong(7),
                )
            }
        }
        // أطفال T6 — القراءة **كاملة** حصراً: الدمج يبني على `persisted.contact` ويُعيد
        // كتابة كل الأطفال عبر writeContactRelationsToSqlite؛ أي عمود غير مقروء هنا
        // سيمحو بيانات مستخدم في كل دلتا (عقد الاتساق ADR-0012).
        val emails = mutableListOf<ContactEmailEntity>()
        conn.usePrepared(
            "SELECT id, contactId, address, type, label, sortOrder FROM contact_emails WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                emails += ContactEmailEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    address = stmt.getText(2),
                    type = stmt.getText(3),
                    label = if (stmt.isNull(4)) null else stmt.getText(4),
                    sortOrder = stmt.getLong(5).toInt(),
                )
            }
        }
        val websites = mutableListOf<ContactWebsiteEntity>()
        conn.usePrepared(
            "SELECT id, contactId, url, sortOrder FROM contact_websites WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                websites += ContactWebsiteEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    url = stmt.getText(2),
                    sortOrder = stmt.getLong(3).toInt(),
                )
            }
        }
        val addresses = mutableListOf<ContactAddressEntity>()
        conn.usePrepared(
            "SELECT id, contactId, street, city, region, postalCode, country, type, sortOrder FROM contact_addresses WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                addresses += ContactAddressEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    street = if (stmt.isNull(2)) null else stmt.getText(2),
                    city = if (stmt.isNull(3)) null else stmt.getText(3),
                    region = if (stmt.isNull(4)) null else stmt.getText(4),
                    postalCode = if (stmt.isNull(5)) null else stmt.getText(5),
                    country = if (stmt.isNull(6)) null else stmt.getText(6),
                    type = stmt.getText(7),
                    sortOrder = stmt.getLong(8).toInt(),
                )
            }
        }
        val dates = mutableListOf<ContactDateEntity>()
        conn.usePrepared(
            "SELECT id, contactId, type, label, month, day, year, sortOrder FROM contact_dates WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                dates += ContactDateEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    type = stmt.getText(2),
                    label = if (stmt.isNull(3)) null else stmt.getText(3),
                    month = stmt.getLong(4).toInt(),
                    day = stmt.getLong(5).toInt(),
                    year = if (stmt.isNull(6)) null else stmt.getLong(6).toInt(),
                    sortOrder = stmt.getLong(7).toInt(),
                )
            }
        }
        val customFields = mutableListOf<ContactCustomFieldEntity>()
        conn.usePrepared(
            "SELECT id, contactId, label, value, sortOrder FROM contact_custom_fields WHERE contactId = ? ORDER BY sortOrder ASC, id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                customFields += ContactCustomFieldEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    label = stmt.getText(2),
                    value = stmt.getText(3),
                    sortOrder = stmt.getLong(4).toInt(),
                )
            }
        }
        return PersistedContact(
            contact = e.toDomain(
                phones = phones.map { p ->
                    ContactPhoneEntity(
                        id = p.id,
                        contactId = p.contactId,
                        raw = p.raw,
                        e164 = p.e164,
                        type = p.type,
                        label = p.label,
                        isPrimary = p.isPrimary,
                        sortOrder = p.sortOrder,
                    )
                },
                arham = arham,
            ).copy(
                emails = emails.map { it.toDomain() },
                websites = websites.map { it.toDomain() },
                addresses = addresses.map { it.toDomain() },
                dates = dates.map { it.toDomain() },
                customFields = customFields.map { it.toDomain() },
            ),
            systemDeletedAt = e.systemDeletedAt,
        )
    }

    /** صف هاتف خام داخل معاملات المزامنة (بلا كيانات Room — قراءة حصراً). */
    private data class ContactPhoneRow(
        val id: Long,
        val contactId: Long,
        val raw: String,
        val e164: String,
        val type: String,
        val label: String?,
        val sortOrder: Int,
        val isPrimary: Boolean,
    )

    /** جهة مخزنة مع حالة tombstone (خارج نموذج النطاق عمداً). */
    private data class PersistedContact(val contact: Contact, val systemDeletedAt: Long?)

    private suspend fun writeCheckpoint(conn: Transactor, key: String, value: String) {
        conn.usePrepared(
            "INSERT OR REPLACE INTO contacts_sync_checkpoints (key, value) VALUES (?, ?)"
        ) { stmt ->
            stmt.bindText(1, key)
            stmt.bindText(2, value)
            stmt.step()
        }
    }

    private suspend fun countRowsWhere(conn: Transactor, sql: String, cutoff: Long): Int =
        conn.usePrepared(sql) { stmt ->
            stmt.bindLong(1, cutoff)
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    /** قراءة صف `contacts` كامل من الموضع الحالي للمؤشر (نفس ترتيب أعمدة SELECT جهات الاتصال). */
    private fun SQLiteStatement.readContactEntityRow(): ContactEntity {
        val displayName = getText(5)
        val fileAs = if (isNull(6)) displayName else getText(6)
        return ContactEntity(
            id = getLong(0),
            systemContactId = getText(1),
            lookupKey = if (isNull(2)) null else getText(2),
            accountType = if (isNull(3)) null else getText(3),
            accountName = if (isNull(4)) null else getText(4),
            displayName = displayName,
            fileAs = fileAs.ifEmpty { displayName },
            prefix = if (isNull(7)) null else getText(7),
            firstName = if (isNull(8)) null else getText(8),
            middleName = if (isNull(9)) null else getText(9),
            lastName = if (isNull(10)) null else getText(10),
            suffix = if (isNull(11)) null else getText(11),
            nickname = if (isNull(12)) null else getText(12),
            phoneticName = if (isNull(13)) null else getText(13),
            company = if (isNull(14)) null else getText(14),
            department = if (isNull(15)) null else getText(15),
            photoUri = if (isNull(16)) null else getText(16),
            category = Category.valueOf(getText(17)),
            isFavorite = getLong(18) != 0L,
            customRingtoneUri = if (isNull(19)) null else getText(19),
            sendToVoicemail = getLong(20) != 0L,
            isBlocked = getLong(21) != 0L,
            notes = if (isNull(22)) null else getText(22),
            linkedServiceCloudId = if (isNull(23)) null else getText(23),
            excludeFromSmartFilter = getLong(24) != 0L,
            normalizedName = getText(25),
            createdAt = getLong(26),
            updatedAt = getLong(27),
            systemDeletedAt = if (isNull(28)) null else getLong(28)
        )
    }

    /**
     * إغلاق قاعدة Room. `close()` في RoomDatabase عامة على Room 3 (مشتركة المنصات) —
     * تُنفَّذ الإغلاق الفعلي عبر هذه الدالة على جميع المنصات.
     */
    fun closeConnection() {
        room.close()
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val BASE_NOW_MS = 1_760_000_000_000L

        private fun seedInitialContacts(): List<Contact> = listOf(
            Contact(
                id = 1L,
                systemContactId = "sys_1",
                name = ContactName(displayName = "أمي الحبيبة", firstName = "أمي"),
                phones = listOf(Phone(raw = "01011112222", e164 = "+201011112222", isPrimary = true)),
                category = Category.FAMILY,
                isFavorite = true,
                arham = ArhamInfo(
                    relation = Relation.MOTHER,
                    frequency = ArhamFrequency.DAILY,
                    lastContactedAt = BASE_NOW_MS - (4 * 3600_000L) // متواصل 🟢
                ),
                normalizedName = ArabicTextNormalizer.normalize("أمي الحبيبة")
            ),
            Contact(
                id = 2L,
                systemContactId = "sys_2",
                name = ContactName(displayName = "العم أحمد", firstName = "أحمد"),
                phones = listOf(Phone(raw = "01022223333", e164 = "+201022223333", isPrimary = true)),
                category = Category.FAMILY,
                isFavorite = true,
                arham = ArhamInfo(
                    relation = Relation.UNCLE_PATERNAL,
                    frequency = ArhamFrequency.WEEKLY,
                    lastContactedAt = BASE_NOW_MS - (12 * DAY_MS) // متأخر 🔴
                ),
                normalizedName = ArabicTextNormalizer.normalize("العم أحمد")
            ),
            Contact(
                id = 3L,
                systemContactId = "sys_3",
                name = ContactName(displayName = "الخالة فاطمة", firstName = "فاطمة"),
                phones = listOf(Phone(raw = "01133334444", e164 = "+201133334444", isPrimary = true)),
                category = Category.FAMILY,
                isFavorite = true,
                arham = ArhamInfo(
                    relation = Relation.AUNT_MATERNAL,
                    frequency = ArhamFrequency.BIWEEKLY,
                    lastContactedAt = BASE_NOW_MS - (11 * DAY_MS) // اقترب الموعد 🟡
                ),
                normalizedName = ArabicTextNormalizer.normalize("الخالة فاطمة")
            ),
            Contact(
                id = 4L,
                systemContactId = "sys_4",
                name = ContactName(displayName = "م. كريم طارق", firstName = "كريم", lastName = "طارق"),
                phones = listOf(Phone(raw = "01244445555", e164 = "+201244445555", isPrimary = true)),
                category = Category.WORK,
                isFavorite = false,
                normalizedName = ArabicTextNormalizer.normalize("م. كريم طارق")
            ),
            Contact(
                id = 5L,
                systemContactId = "sys_5",
                name = ContactName(displayName = "صيدلية الشفاء ٢٤ ساعة", firstName = "صيدلية الشفاء"),
                phones = listOf(Phone(raw = "01555556666", e164 = "+201555556666", isPrimary = true)),
                category = Category.SERVICES,
                isFavorite = true,
                normalizedName = ArabicTextNormalizer.normalize("صيدلية الشفاء ٢٤ ساعة")
            ),
            Contact(
                id = 6L,
                systemContactId = "sys_6",
                name = ContactName(displayName = "الإسعاف المصري"),
                phones = listOf(Phone(raw = "123", e164 = "123", isPrimary = true)),
                category = Category.EMERGENCY,
                isFavorite = false,
                normalizedName = ArabicTextNormalizer.normalize("الإسعاف المصري")
            ),
            Contact(
                id = 7L,
                systemContactId = "sys_7",
                name = ContactName(displayName = "عمر حسن", firstName = "عمر", lastName = "حسن"),
                phones = listOf(Phone(raw = "01099887766", e164 = "+201099887766", isPrimary = true)),
                category = Category.FRIENDS,
                isFavorite = false,
                normalizedName = ArabicTextNormalizer.normalize("عمر حسن")
            )
        )

        private fun seedInitialServices(): List<Service> = listOf(
            Service(
                cloudId = "srv_1",
                name = "صيدلية الشفاء المركزية",
                professionId = "pharmacy",
                professionNameAr = "صيدلية",
                professionNameEn = "Pharmacy",
                phoneE164 = "+201555556666",
                visibility = ServiceVisibility.VERIFIED,
                trustScore = 96.5,
                uniqueCallers = 142,
                retentionRate = 0.89,
                ratingAvg = 2.9,
                ratingCount = 64,
                isHot = true,
                city = "القاهرة",
                distanceKm = 0.8,
                isAvailableNow = true,
                ownerVerified = true,
                isSavedByUser = true
            ),
            Service(
                cloudId = "srv_2",
                name = "ورشة الأمانة للسباكة والصيانة",
                professionId = "plumber",
                professionNameAr = "سباك",
                professionNameEn = "Plumber",
                phoneE164 = "+201066778899",
                visibility = ServiceVisibility.VERIFIED,
                trustScore = 91.0,
                uniqueCallers = 85,
                retentionRate = 0.82,
                ratingAvg = 2.8,
                ratingCount = 39,
                isHot = true,
                city = "القاهرة",
                distanceKm = 1.9,
                isAvailableNow = true,
                ownerVerified = true
            ),
            Service(
                cloudId = "srv_3",
                name = "المهندس محمود للكهرباء الذكية",
                professionId = "electrician",
                professionNameAr = "كهربائي",
                professionNameEn = "Electrician",
                phoneE164 = "+201122334455",
                visibility = ServiceVisibility.COMMUNITY,
                trustScore = 84.0,
                uniqueCallers = 34,
                retentionRate = 0.76,
                ratingAvg = 2.7,
                ratingCount = 18,
                isHot = false,
                city = "الجيزة",
                distanceKm = 3.2,
                isAvailableNow = true,
                ownerVerified = false
            )
        )
    }
}
