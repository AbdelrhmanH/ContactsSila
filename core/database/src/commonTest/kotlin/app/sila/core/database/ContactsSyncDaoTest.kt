package app.sila.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.ContactsSyncBatchResult
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.core.model.ServiceRef
import app.sila.core.model.ServiceVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات DAO المزامنة التزايدية + ترقية المخطط v1→v2 (Issue #23 · ADR-0010 §4/§6/§7).
 */
class ContactsSyncDaoTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private fun syncContact(
        systemContactId: String,
        displayName: String = systemContactId,
        phones: List<Phone> = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
        nickname: String? = null,
        company: String? = null,
    ): Contact = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName, nickname = nickname),
        phones = phones,
        organization = company?.let { app.sila.core.model.Organization(company = it) },
        normalizedName = displayName
    )

    @Test
    fun applySyncBatch_upsertsNewContacts_resolvesIds_andPersistsSnapshotAtomically() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            val result = db.applySyncBatch(
                upserts = listOf(
                    syncContact("plat_1", "أحمد سيد", nickname = "سيد"),
                    syncContact("plat_2", "منى عمر")
                ),
                tombstones = emptyList(),
                snapshotJson = "{\"snapshot\":1}",
                reconcileStampMillis = now,
                nowMillis = now
            )
            assertEquals(2, result.upserted)
            assertEquals(0, result.tombstoned)
            assertEquals(0, result.restored)

            val all = db.observeContacts().first()
            assertTrue(all.any { it.systemContactId == "plat_1" && it.name.displayName == "أحمد سيد" })
            assertTrue(all.any { it.systemContactId == "plat_2" })
            // معرّفات محلولة تلقائياً (id=0 → last_insert_rowid)
            assertTrue(all.first { it.systemContactId == "plat_1" }.id > 0L)

            assertEquals("{\"snapshot\":1}", db.loadSyncSnapshotJson())
            assertEquals(now, db.loadLastReconcileAtMillis())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applySyncBatch_mergesPreservingSilaColumns_andRestoresTombstoned() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            // جهة صِلَة مصنفة ومفضلة وبها أرحام وخدمة مرتبطة:
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "plat_merge",
                    name = ContactName(displayName = "الاسم القديم"),
                    phones = listOf(Phone(raw = "01111111111", e164 = "+201111111111", isPrimary = true)),
                    category = Category.WORK,
                    isFavorite = true,
                    ringtoneUri = "content://ringtone/1",
                    arham = ArhamInfo(relation = Relation.CLOSE_FRIEND, frequency = ArhamFrequency.WEEKLY),
                    linkedService = ServiceRef(
                        cloudId = "srv_1",
                        professionId = "pharmacy",
                        visibility = ServiceVisibility.VERIFIED
                    )
                )
            )
            // قبرها ثم أعد المزامنة بحالة منصة جديدة كلياً:
            db.applySyncBatch(
                upserts = emptyList(),
                tombstones = listOf("plat_merge"),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            assertTrue(db.observeContacts().first().none { it.systemContactId == "plat_merge" })

            val restoreResult = db.applySyncBatch(
                upserts = listOf(
                    syncContact("plat_merge", "الاسم الجديد من المنصة").copy(
                        phones = listOf(Phone(raw = "01222222222", e164 = "+201222222222", isPrimary = true))
                    )
                ),
                tombstones = emptyList(),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now + 1
            )
            assertEquals(1, restoreResult.restored)

            val restored = db.observeContacts().first().first { it.systemContactId == "plat_merge" }
            assertEquals("الاسم الجديد من المنصة", restored.name.displayName)
            assertEquals("+201222222222", restored.phones.single().e164)
            // أعمدة صِلَة محفوظة حرفياً (ADR-0010 §7):
            assertEquals(Category.WORK, restored.category)
            assertTrue(restored.isFavorite)
            assertEquals("content://ringtone/1", restored.ringtoneUri)
            assertNotNull(restored.arham)
            // ملاحظة: `linkedServiceCloudId` عمود مكتوب فقط في المخطط الحالي (ServiceRef يحتاج
            // professionId/visibility غير مخزّنين) — قيد مخطط سابق خارج نطاق T2، موثّق في PR.
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applySyncBatch_tombstoneHidesFromListAndFts_butKeepsRowRecoverable() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            db.applySyncBatch(
                upserts = listOf(syncContact("plat_t", "سعيد مطر")),
                tombstones = emptyList(),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            assertTrue(db.queryFtsContactIds("سعيد").isNotEmpty())

            db.applySyncBatch(
                upserts = emptyList(),
                tombstones = listOf("plat_t"),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            assertTrue(db.observeContacts().first().none { it.systemContactId == "plat_t" })
            assertTrue(db.queryFtsContactIds("سعيد").isEmpty(), "tombstone must remove the row from FTS")

            val indexRow = db.getSyncIndexRows().first { it.systemContactId == "plat_t" }
            assertEquals(now, indexRow.systemDeletedAt)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applySyncBatch_isIdempotent_onReplay() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            val batch = listOf(syncContact("plat_i", "جهة مكررة"))
            val first = db.applySyncBatch(batch, emptyList(), null, null, now)
            val replay = db.applySyncBatch(batch, emptyList(), null, null, now)
            assertEquals(first.upserted, replay.upserted)
            val occurrences = db.observeContacts().first().count { it.systemContactId == "plat_i" }
            assertEquals(1, occurrences)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applySyncBatch_isAtomic_onFailureRollsBackWritesSnapshotAndTombstones() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            // جهة موجودة ستُقبَر داخل المعاملة الفاشلة:
            db.applySyncBatch(
                upserts = listOf(syncContact("plat_a", "جهة قائمة")),
                tombstones = emptyList(),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            db.syncBatchFaultHook = { IllegalStateException("injected failure") }
            try {
                val thrown = runCatching {
                    db.applySyncBatch(
                        upserts = listOf(syncContact("plat_new", "جهة جديدة")),
                        tombstones = listOf("plat_a"),
                        snapshotJson = "{\"corrupt-partial\":true}",
                        reconcileStampMillis = now,
                        nowMillis = now
                    )
                }
                assertTrue(thrown.isFailure, "fault hook must propagate")
            } finally {
                db.syncBatchFaultHook = null
            }
            // لا لقطة ولا ختم (لم تُقدَّم داخل معاملة فشلت):
            assertNull(db.loadSyncSnapshotJson())
            assertNull(db.loadLastReconcileAtMillis())
            // لا صف جديد ولا قبر (تراجع كامل):
            assertTrue(db.observeContacts().first().any { it.systemContactId == "plat_a" })
            assertTrue(db.observeContacts().first().none { it.systemContactId == "plat_new" })
            assertEquals(0, db.getSyncIndexRows().count { it.systemContactId == "plat_a" && it.systemDeletedAt != null })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun contactsFts_matchesArabicNormalization_onSyncedRows() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val now = 1_800_000_000_000L
            db.applySyncBatch(
                upserts = listOf(
                    syncContact("plat_fts", "أحمد محمد", company = "شركة النور")
                ),
                tombstones = emptyList(),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            assertTrue(db.queryFtsContactIds("أحمد").contains(db.getSyncIndexRows().first { it.systemContactId == "plat_fts" }.id))
            assertTrue(db.queryFtsContactIds("النور").isNotEmpty())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_hardDeletesTombstonesOlderThan30Days_only() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val dayMs = 86_400_000L
            val now = 1_800_000_000_000L
            db.applySyncBatch(
                upserts = listOf(
                    syncContact("plat_old", "جهة عتيقة", phones = listOf(Phone(raw = "0111", e164 = "+201111", isPrimary = true))),
                    syncContact("plat_new", "جهة حديثة")
                ),
                tombstones = emptyList(),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = now
            )
            db.applySyncBatch(emptyList(), listOf("plat_old"), null, null, now - (40 * dayMs))
            db.applySyncBatch(emptyList(), listOf("plat_new"), null, null, now - (2 * dayMs))

            val report = db.cleanupExpiredData(now)
            assertEquals(1, report.deletedTombstonedContacts)

            val rows = db.getSyncIndexRows()
            assertNull(rows.firstOrNull { it.systemContactId == "plat_old" }, "expired tombstone must be hard-deleted")
            assertNotNull(rows.firstOrNull { it.systemContactId == "plat_new" }, "recent tombstone must stay recoverable")
            assertTrue(db.observeContacts().first().none { it.systemContactId == "plat_new" })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun migration_v1ToV2_addsParityColumns_preservesData_andBackfillsFts() = runTest {
        val tempDbPath = "build/tmp_test_sync_v1_${Random.nextLong(100_000, 999_999)}.db"

        // 1) أنشئ ملف v1 بالمخطط القديم المخفَّض وبيانات فيه:
        val old = BundledSQLiteDriver().open(tempDbPath)
        try {
            old.execSQL(
                """
                CREATE TABLE contacts (
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
            old.execSQL(
                """
                CREATE TABLE contact_phones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    contactId INTEGER NOT NULL,
                    raw TEXT NOT NULL,
                    e164 TEXT NOT NULL,
                    type TEXT NOT NULL DEFAULT 'MOBILE',
                    isPrimary INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            old.execSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, firstName, lastName, company, " +
                    "category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'old_1', 'خالد سامي', 'خالد', 'سامي', 'مكتب خالد', 'WORK', 1, 'خالد سامي', 10, 20)"
            )
            old.execSQL(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (1, '01033334444', '+201033334444', 'MOBILE', 1)"
            )
        } finally {
            old.close()
        }

        // 2) افتح عبر LocalSilaDatabase (الترقية تحدث في تهيئة المخطط):
        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            // لا بذور: الصفوف المحمّلة من الملف هي كل ما في القاعدة.
            val loaded = db.observeContacts().first()
            assertEquals(1, loaded.size)
            val migrated = loaded.single()
            assertEquals("old_1", migrated.systemContactId)
            assertEquals("خالد سامي", migrated.name.displayName)
            assertEquals(Category.WORK, migrated.category)
            assertTrue(migrated.isFavorite)
            assertEquals("+201033334444", migrated.phones.single().e164)
            assertNull(migrated.lookupKey, "v1 rows carry no parity columns yet")

            // الفهرس مُعاد تعبئته idempotently للصفوف القديمة:
            assertTrue(db.queryFtsContactIds("خالد").contains(1L))

            // المزامنة تعمل فوق الجدول المُرقّى:
            val now = 1_800_000_000_000L
            val result: ContactsSyncBatchResult = db.applySyncBatch(
                upserts = listOf(syncContact("new_1", "جهة بعد الترقية")),
                tombstones = emptyList(),
                snapshotJson = "{\"v\":2}",
                reconcileStampMillis = now,
                nowMillis = now
            )
            assertEquals(1, result.upserted)
            assertEquals("{\"v\":2}", db.loadSyncSnapshotJson())
        } finally {
            db.closeConnection()
        }
    }
}
