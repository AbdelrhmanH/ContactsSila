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
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.toContactEntity
import app.sila.core.database.entity.toDomain
import app.sila.core.database.entity.toEntity
import app.sila.core.database.legacy.LegacyAdoptionException
import app.sila.core.database.legacy.LegacyDatabaseAdopter
import app.sila.core.database.legacy.LegacyFtsDdl
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.database.legacy.SilaDatabaseBackup
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DisputeStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Phone
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
 * - **المخطط:** `SilaRoomDatabase` (`@Database(version = 1)`) هو المنتِج الوحيد للجداول
 *   عبر KSP — لا CREATE TABLE يدوي هنا إطلاقاً. التثبيت الجديد تنشئ Room مباشرة.
 * - **الاعتماد الآمن:** قواعد المستخدمين القديمة (`user_version = 0`) تُعتمد قبل فتح Room
 *   عبر [LegacyDatabaseAdopter]: بصمة بنية → تصنيف صارم → نسخة واقية موثقة
 *   (`sila_local.pre-room-backup` — VACUUM INTO بلا نسخ خام تحت WAL) → شيم ضيق داخل
 *   معاملة واحدة → Room يتحقق ويسجل `user_version = 1` عند أول فتح. ليست `Migration(0,1)`.
 *   النسخة الواقية تُحذف بعد أول عملية Room ناجحة (اعتماد موثق) حصراً.
 * - **مسار الكتابة المنقسم:** الكتابات المركّبة (upsertContact/deleteContact(s)/البذر/
 *   applySyncBatch/cleanupExpiredData) بـ SQL خام **حرفي** داخل
 *   `useWriterConnection { immediateTransaction { … } }` مع خطافات فشل الاختبار قبل
 *   COMMIT؛ والقراءات والكتابات أحادية العبارة عبر DAOs كيانات Room.
 * - **SSOT + الكاش:** الكتابات المركّبة تُحدِّث كاش الذاكرة الذي تعرضه الواجهات **بعد نجاح
 *   COMMIT** حصراً؛ أما التحديثات أحادية الهدف (`updateCategory`/`setFavorite`/
 *   `upsertArhamInfo`/`recordContactCall`) فتبقي الترتيب التاريخي للكتابة أولاً-كاش كما في
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
) : ContactDao, ArhamDao, ServiceDao, DataRetentionCleanupDao, ContactsSyncDao {

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

    /**
     * خطاف حقن فشل داخل معاملة [applySyncBatch] لاختبار التراجع الذرّي — يُستدعى بعد
     * كتابة الدفعة وقبل `COMMIT`؛ الاستثناء الرمي يُرجِع المعاملة كاملة.
     */
    internal var syncBatchFaultHook: (() -> Throwable)? = null

    /** خطاف حقن فشل داخل معاملة [cleanupExpiredData] — بنفس مبدأ [syncBatchFaultHook]. */
    internal var cleanupFaultHook: (() -> Throwable)? = null

    /** نسخة واقية بانتظار الحذف بعد اعتماد موثق (أول عملية Room ناجحة) — `null` = لا شيء. */
    private var pendingBackupDeletion: String? = null

    private var legacyAdopter: LegacyDatabaseAdopter? = null

    /** هل لا تزال هناك نسخة واقية معلَّقة بانتظار الحذف؟ (للاختبارات). */
    internal val hasPendingBackupDeletion: Boolean
        get() = pendingBackupDeletion != null

    /**
     * قاعدة Room الفعلية — تُبنى مرة واحدة: بعد الاعتماد الآمن للمسارات الملفية، أو
     * في الذاكرة لحاضنة الاختبارات. الفتح نفسه (إنشاء الجداول/التحقق/تسجيل
     * `user_version = 1`) يتم بترف عند أول عملية.
     */
    val room: SilaRoomDatabase = buildRoomDatabase()

    private val contactEntityDao = room.contactEntityDao()
    private val arhamEntityDao = room.arhamEntityDao()
    private val serviceEntityDao = room.serviceEntityDao()
    private val callEventEntityDao = room.callEventEntityDao()
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
     * لا تُحذف النسخة إلا بعد نجاح: فتح Room، التحقق من المخطط، `user_version == 1`،
     * `PRAGMA integrity_check == "ok"`، قابلية قراءة `contacts_fts`، وقراءات DAOs الأربعة.
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
                if (version != 1) {
                    throw LegacyAdoptionException(
                        "PR_B adoption verification: expected user_version=1, actual=$version",
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

        return contactEntities.map { entity ->
            entity.toDomain(
                phones = phonesByContact[entity.id].orEmpty(),
                arham = arhamByContact[entity.id],
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
            callEventEntityDao.upsert(
                CallEventEntity(
                    callLogId = callLogId,
                    e164 = e164,
                    direction = CallDirection.OUT,
                    durationSec = 0,
                    timestamp = timestampMillis,
                    source = CallSource.IN_APP,
                )
            )
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
        }
    }

    override suspend fun recordContactCall(contactId: Long, timestampMillis: Long): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            var updatedContact: Contact? = null
            contactsState.update { current ->
                current.map { contact ->
                    if (contact.id == contactId && contact.arham != null) {
                        contact.copy(arham = contact.arham?.copy(lastContactedAt = timestampMillis))
                            .also { u -> updatedContact = u }
                    } else {
                        contact
                    }
                }
            }
            updatedContact?.let { arhamEntityDao.recordContactCall(contactId, timestampMillis) }
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
                    "INSERT INTO call_events (callLogId, e164, direction, durationSec, timestamp, source) VALUES (?, ?, ?, ?, ?, ?)"
                ) { stmt ->
                    stmt.bindText(1, e.callLogId)
                    stmt.bindText(2, e.e164)
                    stmt.bindText(3, e.direction.name)
                    stmt.bindLong(4, e.durationSec.toLong())
                    stmt.bindLong(5, e.timestamp)
                    stmt.bindText(6, e.source.name)
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
                        merged = persisted.contact.copy(
                            // أعمدة المنصة المعكوسة:
                            lookupKey = normalizedIncoming.lookupKey,
                            name = normalizedIncoming.name,
                            photoUri = normalizedIncoming.photoUri,
                            phones = normalizedIncoming.phones,
                            organization = normalizedIncoming.organization,
                            notes = normalizedIncoming.notes,
                            account = normalizedIncoming.account,
                            normalizedName = normalizedIncoming.normalizedName,
                            updatedAt = nowMillis
                        )
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

    // ===== DataRetentionCleanupDao (F-02) =====

    override suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport = withContext(dispatchers.io) {
        dbMutex.withLock {
            val dayMillis = 86_400_000L
            val callCutoff = nowMillis - (365L * dayMillis)
            val inboxCutoff = nowMillis - (30L * dayMillis)
            val disputeCutoff = nowMillis - (90L * dayMillis)
            val tombstoneCutoff = nowMillis - (30L * dayMillis)

            var deletedCalls = 0
            var deletedInbox = 0
            var archivedDisputes = 0
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
                deletedTombstonedContacts = expiredTombstoneIds.size
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

    /** الهواتف + أرحام + FTS لجهة تمت كتابة صفها بمعرّف [contactId]. */
    private suspend fun writeContactRelationsToSqlite(conn: Transactor, contactId: Long, contact: Contact) {
        conn.usePrepared("DELETE FROM contact_phones WHERE contactId = ?") { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        contact.phones.forEach { phone ->
            val pe = phone.toEntity(contactId)
            conn.usePrepared(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (?, ?, ?, ?, ?)"
            ) { stmt ->
                stmt.bindLong(1, pe.contactId)
                stmt.bindText(2, pe.raw)
                stmt.bindText(3, pe.e164)
                stmt.bindText(4, pe.type)
                stmt.bindLong(5, if (pe.isPrimary) 1L else 0L)
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
            """.trimIndent()
        ) { stmt ->
            stmt.bindText(1, systemContactId)
            if (stmt.step()) entity = stmt.readContactEntityRow()
        }
        val e = entity ?: return null
        val phones = mutableListOf<ContactPhoneRow>()
        conn.usePrepared(
            "SELECT id, contactId, raw, e164, type, isPrimary FROM contact_phones WHERE contactId = ? ORDER BY id ASC"
        ) { stmt ->
            stmt.bindLong(1, e.id)
            while (stmt.step()) {
                phones += ContactPhoneRow(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    raw = stmt.getText(2),
                    e164 = stmt.getText(3),
                    type = stmt.getText(4),
                    isPrimary = stmt.getLong(5) != 0L,
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
        return PersistedContact(
            contact = e.toDomain(
                phones = phones.map { p ->
                    ContactPhoneEntity(
                        id = p.id,
                        contactId = p.contactId,
                        raw = p.raw,
                        e164 = p.e164,
                        type = p.type,
                        isPrimary = p.isPrimary,
                    )
                },
                arham = arham,
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
