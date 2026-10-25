package app.sila.core.database.dao

import androidx.room.InvalidationTracker
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.sila.core.common.AppDispatchers
import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.common.PhoneNormalizer
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.ServiceEntity
import app.sila.core.database.entity.toContactEntity
import app.sila.core.database.entity.toDomain
import app.sila.core.database.entity.toEntity
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import app.sila.core.database.SilaRoomDatabase
import app.sila.core.database.SilaTypeConverters

/**
 * تنفيذ قاعدة بيانات Room KMP المحلية التفاعلية (SilaRoomDatabase : RoomDatabase)
 * يعمل بمحرك BundledSQLiteDriver الخاص بـ Room KMP ويستخدم SilaTypeConverters والكيانات العلائقية،
 * مع الاعتماد الكامل على Coroutines (AppDispatchers.io) و Flow التفاعلي.
 */
class LocalSilaDatabase(
    dbFilePath: String = ":memory:",
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val converters: SilaTypeConverters = SilaTypeConverters()
) : SilaRoomDatabase(), ContactDao, ArhamDao, ServiceDao, DataRetentionCleanupDao {

    override fun contactDao(): ContactDao = SqliteContactDao(this)
    override fun arhamDao(): ArhamDao = SqliteArhamDao(this)
    override fun serviceDao(): ServiceDao = SqliteServiceDao(this)
    override fun dataRetentionCleanupDao(): DataRetentionCleanupDao = SqliteDataRetentionCleanupDao(this)

    override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(
        this,
        emptyMap(),
        emptyMap(),
        "contacts",
        "contact_phones",
        "arham",
        "services",
        "call_events",
        "arham_inbox",
        "dispute_logs",
        "scheduled_messages",
        "emergency_ringing_sessions"
    )

    override fun clearAllTables() {
        sqliteConnection.execSQL("DELETE FROM contact_phones")
        sqliteConnection.execSQL("DELETE FROM arham")
        sqliteConnection.execSQL("DELETE FROM contacts")
        sqliteConnection.execSQL("DELETE FROM services")
        sqliteConnection.execSQL("DELETE FROM call_events")
        sqliteConnection.execSQL("DELETE FROM arham_inbox")
        sqliteConnection.execSQL("DELETE FROM dispute_logs")
        contactsState.value = emptyList()
        servicesState.value = emptyList()
    }

    private val dbMutex = Mutex()

    private val sqliteConnection: SQLiteConnection = BundledSQLiteDriver().open(dbFilePath).also { conn ->
        initializeRelationalSchema(conn)
    }

    private val contactsState = MutableStateFlow(loadOrSeedContactsBlocking())
    private val servicesState = MutableStateFlow(loadOrSeedServicesBlocking())

    private fun initializeRelationalSchema(conn: SQLiteConnection) {
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY NOT NULL,
                systemContactId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                firstName TEXT,
                lastName TEXT,
                company TEXT,
                photoUri TEXT,
                category TEXT NOT NULL,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                excludeFromSmartFilter INTEGER NOT NULL DEFAULT 0,
                normalizedName TEXT NOT NULL,
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_category ON contacts(category)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_norm_name ON contacts(normalizedName)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_fav ON contacts(isFavorite)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_phones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contactId INTEGER NOT NULL,
                raw TEXT NOT NULL,
                e164 TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'MOBILE',
                isPrimary INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_cid ON contact_phones(contactId)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_e164 ON contact_phones(e164)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS arham (
                contactId INTEGER PRIMARY KEY NOT NULL,
                relation TEXT NOT NULL,
                layer TEXT NOT NULL,
                parentContactId INTEGER,
                frequency TEXT NOT NULL,
                lastContactedAt INTEGER,
                pausedUntil INTEGER,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS services (
                cloudId TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                professionId TEXT NOT NULL,
                professionNameAr TEXT NOT NULL,
                professionNameEn TEXT NOT NULL,
                phoneE164 TEXT NOT NULL,
                visibility TEXT NOT NULL,
                trustScore REAL NOT NULL,
                uniqueCallers INTEGER NOT NULL,
                retentionRate REAL NOT NULL,
                ratingAvg REAL NOT NULL,
                ratingCount INTEGER NOT NULL,
                isHot INTEGER NOT NULL DEFAULT 0,
                city TEXT,
                ownerVerified INTEGER NOT NULL DEFAULT 0,
                isSavedByUser INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_services_phone ON services(phoneE164)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_services_prof ON services(professionId)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS call_events (
                callLogId TEXT PRIMARY KEY NOT NULL,
                e164 TEXT NOT NULL,
                direction TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_call_events_ts ON call_events(timestamp)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS arham_inbox (
                id INTEGER PRIMARY KEY NOT NULL,
                senderUid TEXT NOT NULL,
                senderName TEXT NOT NULL,
                recipientUid TEXT NOT NULL,
                messageText TEXT NOT NULL,
                direction TEXT NOT NULL,
                status TEXT NOT NULL,
                sentAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_arham_inbox_sent ON arham_inbox(sentAt)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dispute_logs (
                id INTEGER PRIMARY KEY NOT NULL,
                serviceCloudId TEXT NOT NULL,
                description TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                resolvedAt INTEGER
            )
            """.trimIndent()
        )
    }

    private fun loadOrSeedContactsBlocking(): List<Contact> {
        val loaded = readAllContactsFromSqlite()
        if (loaded.isNotEmpty()) return loaded

        val initial = seedInitialContacts()
        sqliteConnection.execSQL("BEGIN TRANSACTION")
        try {
            initial.forEach { writeContactToSqlite(it) }
            sqliteConnection.execSQL("COMMIT")
        } catch (t: Throwable) {
            sqliteConnection.execSQL("ROLLBACK")
            throw t
        }
        return initial
    }

    private fun loadOrSeedServicesBlocking(): List<Service> {
        val loaded = readAllServicesFromSqlite()
        if (loaded.isNotEmpty()) return loaded

        val initial = seedInitialServices()
        sqliteConnection.execSQL("BEGIN TRANSACTION")
        try {
            initial.forEach { writeServiceToSqlite(it) }
            sqliteConnection.execSQL("COMMIT")
        } catch (t: Throwable) {
            sqliteConnection.execSQL("ROLLBACK")
            throw t
        }
        return initial
    }

    private fun readAllContactsFromSqlite(): List<Contact> {
        val contactEntities = mutableListOf<ContactEntity>()
        sqliteConnection.prepare(
            """
            SELECT id, systemContactId, displayName, firstName, lastName, company, photoUri,
                   category, isFavorite, excludeFromSmartFilter, normalizedName, createdAt, updatedAt
            FROM contacts ORDER BY id ASC
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                contactEntities += ContactEntity(
                    id = stmt.getLong(0),
                    systemContactId = stmt.getText(1),
                    displayName = stmt.getText(2),
                    firstName = if (stmt.isNull(3)) null else stmt.getText(3),
                    lastName = if (stmt.isNull(4)) null else stmt.getText(4),
                    company = if (stmt.isNull(5)) null else stmt.getText(5),
                    photoUri = if (stmt.isNull(6)) null else stmt.getText(6),
                    category = Category.valueOf(stmt.getText(7)),
                    isFavorite = stmt.getLong(8) != 0L,
                    excludeFromSmartFilter = stmt.getLong(9) != 0L,
                    normalizedName = stmt.getText(10),
                    createdAt = stmt.getLong(11),
                    updatedAt = stmt.getLong(12)
                )
            }
        }
        if (contactEntities.isEmpty()) return emptyList()

        val phonesByContact = mutableMapOf<Long, MutableList<ContactPhoneEntity>>()
        sqliteConnection.prepare(
            "SELECT id, contactId, raw, e164, type, isPrimary FROM contact_phones ORDER BY id ASC"
        ).use { stmt ->
            while (stmt.step()) {
                val phoneEntity = ContactPhoneEntity(
                    id = stmt.getLong(0),
                    contactId = stmt.getLong(1),
                    raw = stmt.getText(2),
                    e164 = stmt.getText(3),
                    type = stmt.getText(4),
                    isPrimary = stmt.getLong(5) != 0L
                )
                phonesByContact.getOrPut(phoneEntity.contactId) { mutableListOf() }.add(phoneEntity)
            }
        }

        val arhamByContact = mutableMapOf<Long, ArhamEntity>()
        sqliteConnection.prepare(
            """
            SELECT contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt
            FROM arham
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                val cid = stmt.getLong(0)
                arhamByContact[cid] = ArhamEntity(
                    contactId = cid,
                    relation = Relation.valueOf(stmt.getText(1)),
                    layer = KinshipLayer.valueOf(stmt.getText(2)),
                    parentContactId = if (stmt.isNull(3)) null else stmt.getLong(3),
                    frequency = ArhamFrequency.valueOf(stmt.getText(4)),
                    lastContactedAt = if (stmt.isNull(5)) null else stmt.getLong(5),
                    pausedUntil = if (stmt.isNull(6)) null else stmt.getLong(6),
                    createdAt = stmt.getLong(7)
                )
            }
        }

        return contactEntities.map { entity ->
            entity.toDomain(
                phones = phonesByContact[entity.id].orEmpty(),
                arham = arhamByContact[entity.id]
            )
        }
    }

    private fun writeContactToSqlite(contact: Contact) {
        val entity = contact.toContactEntity()
        sqliteConnection.prepare(
            """
            INSERT OR REPLACE INTO contacts (
                id, systemContactId, displayName, firstName, lastName, company, photoUri,
                category, isFavorite, excludeFromSmartFilter, normalizedName, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, entity.id)
            stmt.bindText(2, entity.systemContactId)
            stmt.bindText(3, entity.displayName)
            entity.firstName?.let { stmt.bindText(4, it) } ?: stmt.bindNull(4)
            entity.lastName?.let { stmt.bindText(5, it) } ?: stmt.bindNull(5)
            entity.company?.let { stmt.bindText(6, it) } ?: stmt.bindNull(6)
            entity.photoUri?.let { stmt.bindText(7, it) } ?: stmt.bindNull(7)
            stmt.bindText(8, entity.category.name)
            stmt.bindLong(9, if (entity.isFavorite) 1L else 0L)
            stmt.bindLong(10, if (entity.excludeFromSmartFilter) 1L else 0L)
            stmt.bindText(11, entity.normalizedName)
            stmt.bindLong(12, entity.createdAt)
            stmt.bindLong(13, entity.updatedAt)
            stmt.step()
        }

        sqliteConnection.prepare("DELETE FROM contact_phones WHERE contactId = ?").use { stmt ->
            stmt.bindLong(1, entity.id)
            stmt.step()
        }
        contact.phones.forEach { phone ->
            val pe = phone.toEntity(entity.id)
            sqliteConnection.prepare(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
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
            val ae = arham.toEntity(entity.id, entity.createdAt)
            sqliteConnection.prepare(
                """
                INSERT OR REPLACE INTO arham (
                    contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
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
            sqliteConnection.prepare("DELETE FROM arham WHERE contactId = ?").use { stmt ->
                stmt.bindLong(1, entity.id)
                stmt.step()
            }
        }
    }

    private fun deleteContactFromSqlite(contactId: Long) {
        sqliteConnection.prepare("DELETE FROM contact_phones WHERE contactId = ?").use { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        sqliteConnection.prepare("DELETE FROM arham WHERE contactId = ?").use { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
        sqliteConnection.prepare("DELETE FROM contacts WHERE id = ?").use { stmt ->
            stmt.bindLong(1, contactId)
            stmt.step()
        }
    }

    private fun readAllServicesFromSqlite(): List<Service> {
        val entities = mutableListOf<ServiceEntity>()
        sqliteConnection.prepare(
            """
            SELECT cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164,
                   visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount,
                   isHot, city, ownerVerified, isSavedByUser
            FROM services
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                entities += ServiceEntity(
                    cloudId = stmt.getText(0),
                    name = stmt.getText(1),
                    professionId = stmt.getText(2),
                    professionNameAr = stmt.getText(3),
                    professionNameEn = stmt.getText(4),
                    phoneE164 = stmt.getText(5),
                    visibility = ServiceVisibility.valueOf(stmt.getText(6)),
                    trustScore = stmt.getDouble(7),
                    uniqueCallers = stmt.getLong(8).toInt(),
                    retentionRate = stmt.getDouble(9),
                    ratingAvg = stmt.getDouble(10),
                    ratingCount = stmt.getLong(11).toInt(),
                    isHot = stmt.getLong(12) != 0L,
                    city = if (stmt.isNull(13)) null else stmt.getText(13),
                    ownerVerified = stmt.getLong(14) != 0L,
                    isSavedByUser = stmt.getLong(15) != 0L
                )
            }
        }
        return entities.map { it.toDomain() }
    }

    private fun writeServiceToSqlite(service: Service) {
        val entity = service.toEntity()
        sqliteConnection.prepare(
            """
            INSERT OR REPLACE INTO services (
                cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164,
                visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount,
                isHot, city, ownerVerified, isSavedByUser
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.bindText(1, entity.cloudId)
            stmt.bindText(2, entity.name)
            stmt.bindText(3, entity.professionId)
            stmt.bindText(4, entity.professionNameAr)
            stmt.bindText(5, entity.professionNameEn)
            stmt.bindText(6, entity.phoneE164)
            stmt.bindText(7, entity.visibility.name)
            stmt.bindDouble(8, entity.trustScore)
            stmt.bindLong(9, entity.uniqueCallers.toLong())
            stmt.bindDouble(10, entity.retentionRate)
            stmt.bindDouble(11, entity.ratingAvg)
            stmt.bindLong(12, entity.ratingCount.toLong())
            stmt.bindLong(13, if (entity.isHot) 1L else 0L)
            entity.city?.let { stmt.bindText(14, it) } ?: stmt.bindNull(14)
            stmt.bindLong(15, if (entity.ownerVerified) 1L else 0L)
            stmt.bindLong(16, if (entity.isSavedByUser) 1L else 0L)
            stmt.step()
        }
    }

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
            writeContactToSqlite(normalized)
            contactsState.update { current ->
                val existingIndex = current.indexOfFirst { it.id == normalized.id }
                if (existingIndex >= 0) {
                    current.toMutableList().apply { set(existingIndex, normalized) }
                } else {
                    current + normalized
                }
            }
        }
    }

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
            updatedContact?.let { writeContactToSqlite(it) }
        }
    }

    override suspend fun deleteContact(contactId: Long): Unit = withContext(dispatchers.io) {
        dbMutex.withLock {
            deleteContactFromSqlite(contactId)
            contactsState.update { current -> current.filterNot { it.id == contactId } }
        }
    }

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
            updatedContact?.let { writeContactToSqlite(it) }
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
            updatedContact?.let { writeContactToSqlite(it) }
        }
    }

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
            services.forEach { writeServiceToSqlite(it) }
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

    fun seedCallEventsForTest(events: List<CallEventEntity>) {
        sqliteConnection.execSQL("DELETE FROM call_events")
        events.forEach { e ->
            sqliteConnection.prepare(
                "INSERT INTO call_events (callLogId, e164, direction, durationSec, timestamp, source) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
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

    fun seedInboxForTest(messages: List<ArhamInboxEntity>) {
        sqliteConnection.execSQL("DELETE FROM arham_inbox")
        messages.forEach { m ->
            sqliteConnection.prepare(
                "INSERT INTO arham_inbox (id, senderUid, senderName, recipientUid, messageText, direction, status, sentAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
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

    fun seedDisputesForTest(disputes: List<DisputeLogEntity>) {
        sqliteConnection.execSQL("DELETE FROM dispute_logs")
        disputes.forEach { d ->
            sqliteConnection.prepare(
                "INSERT INTO dispute_logs (id, serviceCloudId, description, status, createdAt, updatedAt, resolvedAt) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
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

    override suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport = withContext(dispatchers.io) {
        dbMutex.withLock {
            val dayMillis = 86_400_000L
            val callCutoff = nowMillis - (365L * dayMillis)
            val inboxCutoff = nowMillis - (30L * dayMillis)
            val disputeCutoff = nowMillis - (90L * dayMillis)

            val deletedCalls = countRowsWhere("SELECT COUNT(*) FROM call_events WHERE timestamp < ?", callCutoff)
            sqliteConnection.prepare("DELETE FROM call_events WHERE timestamp < ?").use { stmt ->
                stmt.bindLong(1, callCutoff)
                stmt.step()
            }

            val deletedInbox = countRowsWhere("SELECT COUNT(*) FROM arham_inbox WHERE sentAt < ?", inboxCutoff)
            sqliteConnection.prepare("DELETE FROM arham_inbox WHERE sentAt < ?").use { stmt ->
                stmt.bindLong(1, inboxCutoff)
                stmt.step()
            }

            val archivedDisputes = countRowsWhere(
                "SELECT COUNT(*) FROM dispute_logs WHERE status = '${DisputeStatus.RESOLVED.name}' AND resolvedAt IS NOT NULL AND resolvedAt < ?",
                disputeCutoff
            )
            sqliteConnection.prepare(
                "UPDATE dispute_logs SET status = ?, updatedAt = ? WHERE status = ? AND resolvedAt IS NOT NULL AND resolvedAt < ?"
            ).use { stmt ->
                stmt.bindText(1, DisputeStatus.ARCHIVED.name)
                stmt.bindLong(2, nowMillis)
                stmt.bindText(3, DisputeStatus.RESOLVED.name)
                stmt.bindLong(4, disputeCutoff)
                stmt.step()
            }

            CleanupReport(
                deletedCallEvents = deletedCalls,
                deletedInboxMessages = deletedInbox,
                archivedDisputes = archivedDisputes
            )
        }
    }

    private fun countRowsWhere(sql: String, cutoff: Long): Int {
        sqliteConnection.prepare(sql).use { stmt ->
            stmt.bindLong(1, cutoff)
            return if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
    }

    override fun close() {
        sqliteConnection.close()
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
