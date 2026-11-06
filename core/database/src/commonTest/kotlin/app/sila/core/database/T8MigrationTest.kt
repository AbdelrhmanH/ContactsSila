package app.sila.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.legacy.LegacySchemaUnknownException
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.model.CallSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات ترحيل المخطط v2→v3 (T8-02 · Issue #29) — مصفوفة §J من تقرير بوابة T8-02.
 *
 * المساران يستهلكان نفس DDL المصدر الوحيد ([CallEventsV3Ddl]):
 * 1. [MIGRATION_2_3] على اتصال خام بشكل v2 (ذاكرة أو ملف) — يُستدعى مباشرةً
 *    (نفس الاستدعاء الذي تُشغِّله Room داخل معاملتها الحصرية).
 * 2. شيم الاعتماد لقواعد إصدار-صفر — عبر فتح [LocalSilaDatabase] على ملف جاهز.
 *
 * البوابة الحاسمة (§G.7-7): الفتح الإنتاجي عبر Room على قاعدة مُرحَّلة يجب أن يمر
 * بلا أي استثناء تحقق مخطط — هذا يثبت أن الناتج الفيزيائي يطابق `3.json` حرفاً.
 */
class T8MigrationTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    // ===== بنية التحقق =====

    /** مواصفة عمود كما تقرأها `PRAGMA table_info` (notnull/pk كأعداد). */
    private data class ColumnSpec(val name: String, val type: String, val notNull: Int, val pkPosition: Int)

    /** صف v3 كامل كما يُقرأ بـ SQL خام — للتحقق الحرفي من خريطة §H.2. */
    private data class V3Row(
        val callLogId: String,
        val e164: String,
        val timestamp: Long,
        val primarySource: String,
        val evidenceSources: String,
        val remoteNumber: String?,
        val contactId: Long?,
        val direction: String?,
        val startedAt: Long?,
        val connectedAt: Long?,
        val endedAt: Long?,
        val durationSec: Long?,
        val outcome: String,
        val association: String,
        val confirmation: String,
        val confidence: String,
        val dedupeKey: String?,
        val simSlot: Long?,
    )

    /** شكل v3 المتوقع حرفاً — 18 عموداً بترتيب §F.2 (المفتاح أول العواميد أن يكون PK وحيداً). */
    private val v3ExpectedColumns = listOf(
        ColumnSpec("callLogId", "TEXT", 1, 1),
        ColumnSpec("e164", "TEXT", 1, 0),
        ColumnSpec("timestamp", "INTEGER", 1, 0),
        ColumnSpec("primarySource", "TEXT", 1, 0),
        ColumnSpec("evidenceSources", "TEXT", 1, 0),
        ColumnSpec("remoteNumber", "TEXT", 0, 0),
        ColumnSpec("contactId", "INTEGER", 0, 0),
        ColumnSpec("direction", "TEXT", 0, 0),
        ColumnSpec("startedAt", "INTEGER", 0, 0),
        ColumnSpec("connectedAt", "INTEGER", 0, 0),
        ColumnSpec("endedAt", "INTEGER", 0, 0),
        ColumnSpec("durationSec", "INTEGER", 0, 0),
        ColumnSpec("outcome", "TEXT", 1, 0),
        ColumnSpec("association", "TEXT", 1, 0),
        ColumnSpec("confirmation", "TEXT", 1, 0),
        ColumnSpec("confidence", "TEXT", 1, 0),
        ColumnSpec("dedupeKey", "TEXT", 0, 0),
        ColumnSpec("simSlot", "INTEGER", 0, 0),
    )

    /** شكل D4 المتوقع حرفاً — الحد الأدنى الموحد (قرار D4). */
    private val d4ExpectedColumns = listOf(
        ColumnSpec("id", "INTEGER", 1, 1),
        ColumnSpec("contactId", "INTEGER", 1, 0),
        ColumnSpec("startedAt", "INTEGER", 1, 0),
        ColumnSpec("lastAttemptAt", "INTEGER", 1, 0),
        ColumnSpec("attempts", "INTEGER", 1, 0),
        ColumnSpec("status", "TEXT", 1, 0),
    )

    /** الصف المتوقع لصف إرثي v2 بعد الخريطة §H.2 حرفاً (محفوظ + مشتق + ثمانية NULL). */
    private fun expectedH2Row(
        callLogId: String,
        e164: String,
        timestamp: Long,
        source: String,
        direction: String?,
        durationSec: Long,
        missed: Boolean,
    ) = V3Row(
        callLogId = callLogId,
        e164 = e164,
        timestamp = timestamp,
        primarySource = source,
        evidenceSources = source,
        remoteNumber = null,
        contactId = null,
        direction = direction,
        startedAt = null,
        connectedAt = null,
        endedAt = null,
        durationSec = durationSec,
        outcome = if (missed) "UNANSWERED" else "UNKNOWN",
        association = "UNASSOCIATED",
        confirmation = "NOT_REQUIRED",
        confidence = "LOW",
        dedupeKey = null,
        simSlot = null,
    )

    // ===== مساعدات خام =====

    private fun openMemoryDb() = BundledSQLiteDriver().open(":memory:")

    /** جدول `call_events` بشكل v2 الحرفي (ستة أعمدة + الفهرس الوحيد) — كما أنشأته Room v2. */
    private suspend fun createV2CallEvents(db: SQLiteConnection) {
        db.executeSQL(
            "CREATE TABLE IF NOT EXISTS `call_events` (" +
                "`callLogId` TEXT NOT NULL, `e164` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                "`durationSec` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "PRIMARY KEY(`callLogId`))"
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_call_events_ts` ON `call_events` (`timestamp`)")
    }

    private suspend fun seedLegacyCallEvent(
        db: SQLiteConnection,
        callLogId: String,
        e164: String,
        direction: String,
        durationSec: Long,
        timestamp: Long,
        source: String,
    ) {
        db.prepare(
            "INSERT INTO call_events (callLogId, e164, direction, durationSec, timestamp, source) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { stmt ->
            stmt.bindText(1, callLogId)
            stmt.bindText(2, e164)
            stmt.bindText(3, direction)
            stmt.bindLong(4, durationSec)
            stmt.bindLong(5, timestamp)
            stmt.bindText(6, source)
            stmt.step()
        }
    }

    private suspend fun readColumns(db: SQLiteConnection, table: String): List<ColumnSpec> =
        db.prepare("PRAGMA table_info(`$table`)").use { stmt ->
            buildList {
                while (stmt.step()) {
                    add(
                        ColumnSpec(
                            name = stmt.getText(1),
                            type = stmt.getText(2),
                            notNull = stmt.getLong(3).toInt(),
                            pkPosition = stmt.getLong(5).toInt(),
                        )
                    )
                }
            }
        }

    private suspend fun columnCount(db: SQLiteConnection, table: String): Int = readColumns(db, table).size

    private suspend fun readAllV3Rows(db: SQLiteConnection): List<V3Row> =
        db.prepare(
            "SELECT callLogId, e164, timestamp, primarySource, evidenceSources, remoteNumber, contactId, " +
                "direction, startedAt, connectedAt, endedAt, durationSec, outcome, association, confirmation, " +
                "confidence, dedupeKey, simSlot FROM call_events ORDER BY callLogId"
        ).use { stmt ->
            buildList {
                while (stmt.step()) {
                    fun text(i: Int) = if (stmt.isNull(i)) null else stmt.getText(i)
                    fun longOrNull(i: Int) = if (stmt.isNull(i)) null else stmt.getLong(i)
                    add(
                        V3Row(
                            callLogId = stmt.getText(0),
                            e164 = stmt.getText(1),
                            timestamp = stmt.getLong(2),
                            primarySource = stmt.getText(3),
                            evidenceSources = stmt.getText(4),
                            remoteNumber = text(5),
                            contactId = longOrNull(6),
                            direction = text(7),
                            startedAt = longOrNull(8),
                            connectedAt = longOrNull(9),
                            endedAt = longOrNull(10),
                            durationSec = longOrNull(11),
                            outcome = stmt.getText(12),
                            association = stmt.getText(13),
                            confirmation = stmt.getText(14),
                            confidence = stmt.getText(15),
                            dedupeKey = text(16),
                            simSlot = longOrNull(17),
                        )
                    )
                }
            }
        }

    private suspend fun countRows(db: SQLiteConnection, table: String): Long =
        db.prepare("SELECT COUNT(*) FROM `$table`").use { stmt ->
            assertTrue(stmt.step(), "COUNT يعيد صفاً دائماً")
            stmt.getLong(0)
        }

    private suspend fun tableExists(db: SQLiteConnection, name: String): Boolean =
        db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = ? AND type = 'table'").use { stmt ->
            stmt.bindText(1, name)
            stmt.step() && stmt.getLong(0) > 0L
        }

    private suspend fun userVersionOfConn(db: SQLiteConnection): Int =
        db.prepare("PRAGMA user_version").use { stmt ->
            assertTrue(stmt.step())
            stmt.getLong(0).toInt()
        }

    private suspend fun userVersionOfFile(path: String): Int =
        BundledSQLiteDriver().open(path).use { conn -> userVersionOfConn(conn) }

    /** تفريغ صفوف شاهد (كل الأعمدة [columns] الأولى كنص/isNull) للمقارنة الحرفية قبل/بعد. */
    private suspend fun dumpWitness(db: SQLiteConnection, sql: String, columns: Int): List<List<String?>> =
        db.prepare(sql).use { stmt ->
            buildList {
                while (stmt.step()) {
                    add((0 until columns).map { i -> if (stmt.isNull(i)) null else stmt.getText(i) })
                }
            }
        }

    private suspend fun readFtsCreateSql(db: SQLiteConnection): String? =
        db.prepare("SELECT sql FROM sqlite_master WHERE name = 'contacts_fts'").use { stmt ->
            if (stmt.step() && !stmt.isNull(0)) stmt.getText(0) else null
        }

    /** عدة تشهد §J-5: تفريغ جداول الشاهد غير المتصلة بالترحيل + FTS. */
    private data class WitnessSnapshot(
        val contacts: List<List<String?>>,
        val phones: List<List<String?>>,
        val arham: List<List<String?>>,
        val dates: List<List<String?>>,
        val fts: List<List<String?>>,
        val ftsCreateSql: String?,
    )

    private suspend fun readWitnessSnapshot(path: String): WitnessSnapshot =
        BundledSQLiteDriver().open(path).use { conn ->
            WitnessSnapshot(
                contacts = dumpWitness(
                    conn,
                    "SELECT id, systemContactId, displayName, category, isFavorite, normalizedName, systemDeletedAt " +
                        "FROM contacts ORDER BY id",
                    7,
                ),
                phones = dumpWitness(
                    conn,
                    "SELECT id, contactId, raw, e164, type, isPrimary, label, sortOrder " +
                        "FROM contact_phones ORDER BY id",
                    8,
                ),
                arham = dumpWitness(
                    conn,
                    "SELECT contactId, relation, layer, parentContactId, frequency, lastContactedAt, pausedUntil, createdAt " +
                        "FROM arham ORDER BY contactId",
                    8,
                ),
                dates = dumpWitness(
                    conn,
                    "SELECT id, contactId, type, label, month, day, year, sortOrder " +
                        "FROM contact_dates ORDER BY id",
                    8,
                ),
                fts = dumpWitness(
                    conn,
                    "SELECT docid, displayName, nickname, company, department, notes, normalizedName " +
                        "FROM contacts_fts ORDER BY docid",
                    7,
                ),
                ftsCreateSql = readFtsCreateSql(conn),
            )
        }

    /** الجداول المدارة الثلاثة عشر (صورة v2) — عداداتها للتحقق من عدم الهدم (§J-8). */
    private val v2ManagedTables = listOf(
        "contacts", "contact_phones", "contact_emails", "contact_addresses", "contact_dates",
        "contact_custom_fields", "contact_websites", "arham", "services", "call_events",
        "arham_inbox", "dispute_logs", "contacts_sync_checkpoints",
    )

    private suspend fun readManagedTableCounts(path: String): Map<String, Long> =
        BundledSQLiteDriver().open(path).use { conn ->
            v2ManagedTables.associateWith { table -> countRows(conn, table) }
        }

    // ===== §J-1: قاعدة v2 فارغة → صورة v3 حرفاً + الفهرس الوحيد + D4 =====

    @Test
    fun migration23_emptyV2_producesExactV3Shape_singleIndex_andEmergencyTable() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)

            MIGRATION_2_3.migrate(db)

            assertEquals(v3ExpectedColumns, readColumns(db, "call_events"), "شكل v3 حرفاً — 18 عموداً بترتيب §F.2")

            // فهرس واحد حصراً بأصل 'c' (مُعرَّف من المستخدم) — غير فريد وعلى timestamp.
            val userIndices = mutableListOf<Triple<String, Long, String>>()
            db.prepare("PRAGMA index_list(`call_events`)").use { stmt ->
                while (stmt.step()) {
                    if (stmt.getText(3) == "c") {
                        userIndices += Triple(stmt.getText(1), stmt.getLong(2), stmt.getText(3))
                    }
                }
            }
            assertEquals(listOf(Triple("idx_call_events_ts", 0L, "c")), userIndices, "فهرس واحد غير فريد")
            db.prepare("PRAGMA index_info(`idx_call_events_ts`)").use { stmt ->
                assertTrue(stmt.step(), "الفهرس موجود")
                assertEquals("timestamp", stmt.getText(2), "عمود واحد: timestamp")
                assertFalse(stmt.step(), "بلا أعمدة إضافية")
            }

            assertEquals(0L, countRows(db, "call_events"), "بلا صفوف بعد ترحيل قاعدة فارغة")
            assertEquals(d4ExpectedColumns, readColumns(db, "emergency_ringing_sessions"), "D4 بصورتها الدنيا الموحدة")
            assertFalse(tableExists(db, "call_events_v3_rebuild"), "لا بقايا جدول مؤقت")
        } finally {
            db.close()
        }
    }

    // ===== §J-2: صفوف إرثية صالحة (كل المصادر والاتجاهات) → خريطة §H.2 حرفاً =====

    @Test
    fun migration23_validLegacyRows_verbatimPreservation_andH2Derivations() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            seedLegacyCallEvent(db, "log_in", "+201000000002", "IN", 120, 2000L, "CALL_LOG")
            seedLegacyCallEvent(db, "app_out", "+201000000003", "OUT", 0, 3000L, "IN_APP")
            seedLegacyCallEvent(db, "obs_missed", "+201000000004", "MISSED", 0, 4000L, "OBSERVER")
            seedLegacyCallEvent(db, "app_missed", "+201000000005", "MISSED", 30, 5000L, "IN_APP")

            MIGRATION_2_3.migrate(db)

            assertEquals(
                listOf(
                    expectedH2Row("app_missed", "+201000000005", 5000L, "IN_APP", direction = null, durationSec = 30L, missed = true),
                    expectedH2Row("app_out", "+201000000003", 3000L, "IN_APP", direction = "OUT", durationSec = 0L, missed = false),
                    expectedH2Row("log_in", "+201000000002", 2000L, "CALL_LOG", direction = "IN", durationSec = 120L, missed = false),
                    expectedH2Row("log_out", "+201000000001", 1000L, "CALL_LOG", direction = "OUT", durationSec = 45L, missed = false),
                    expectedH2Row("obs_missed", "+201000000004", 4000L, "OBSERVER", direction = null, durationSec = 0L, missed = true),
                ),
                readAllV3Rows(db),
                "خريطة §H.2 حرفاً: callLogId/e164/timestamp/durationSec محفوظة، source→primarySource مع مجموعة أدلة مفردة " +
                    "(حتى القيم المهجورة OBSERVER تُنقل كما هي)، MISSED→NULL+UNANSWERED، والإرث الآخر UNKNOWN، " +
                    "والثوابت الثلاث (UNASSOCIATED/NOT_REQUIRED/LOW)، والثمانية الجديدة NULL",
            )
        } finally {
            db.close()
        }
    }

    // ===== §J-3: قلب القيم الفارغة — صفر يبقى صفراً وMISSED تصير NULL وبلا فقد صفوف =====

    @Test
    fun migration23_zeroDurationStaysZero_missedFlipsToNull_noRowDropped() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "out_zero", "+201000000001", "OUT", 0, 1000L, "CALL_LOG")
            seedLegacyCallEvent(db, "missed_row", "+201000000002", "MISSED", 0, 2000L, "CALL_LOG")
            seedLegacyCallEvent(db, "out_norm", "+201000000003", "OUT", 45, 3000L, "CALL_LOG")

            MIGRATION_2_3.migrate(db)

            val rows = readAllV3Rows(db).associateBy { it.callLogId }
            assertEquals(3, rows.size, "لا صف ضاع")
            assertEquals(0L, rows.getValue("out_zero").durationSec, "المدة صفر تُحفظ صفراً — لا تُقلَّب NULL (§H.2)")
            assertNull(rows.getValue("missed_row").direction, "MISSED → direction NULL حصراً")
            assertEquals("UNANSWERED", rows.getValue("missed_row").outcome, "MISSED → UNANSWERED")
            assertEquals("OUT", rows.getValue("out_norm").direction)
        } finally {
            db.close()
        }
    }

    // ===== §J-4: نصوص تعداد تالفة → فشل آمن قبل أي كتابة =====

    @Test
    fun migration23_malformedDirectionText_failsClosedBeforeAnyWrite() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "ok_row", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            db.executeSQL(
                "INSERT INTO call_events (callLogId, e164, direction, durationSec, timestamp, source) " +
                    "VALUES ('bad_row', '+201000000009', 'WEIRD', 0, 2000, 'CALL_LOG')"
            )

            val ex = assertFailsWith<IllegalStateException> { MIGRATION_2_3.migrate(db) }
            assertTrue(ex.message!!.contains("fail-closed"), "الرسالة تشخيصية بلا PII: ${ex.message}")

            // القاعدة كما كانت حرفاً — التحقق الأول رمى قبل أي DDL/DML (§H.4):
            assertEquals(6, columnCount(db, "call_events"), "شكل v2 لم يُمس")
            assertEquals(2L, countRows(db, "call_events"), "الصفوف كما هي — بلا حذف ولا نسخ جزئي")
            assertFalse(tableExists(db, "emergency_ringing_sessions"), "لا D4 قبل نجاح التحقق")
            assertFalse(tableExists(db, "call_events_v3_rebuild"), "لا جدول مؤقت")
        } finally {
            db.close()
        }
    }

    @Test
    fun migration23_malformedSourceText_failsClosedBeforeAnyWrite() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "ghost_row", "+201000000001", "OUT", 45, 1000L, "GHOST")

            assertFailsWith<IllegalStateException> { MIGRATION_2_3.migrate(db) }

            assertEquals(6, columnCount(db, "call_events"), "شكل v2 لم يُمس")
            assertEquals(1L, countRows(db, "call_events"))
            assertFalse(tableExists(db, "emergency_ringing_sessions"), "لا D4 قبل نجاح التحقق")
        } finally {
            db.close()
        }
    }

    // ===== §J-5: الجداول غير المتصلة لم تُمس حرفاً (شاهدة كاملة على ملف v2) =====

    @Test
    fun migration23_unrelatedTables_untouched_includingFts() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_witness_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'sys_1', 'نور الدين', 'FAMILY', 1, 'نور الدين', 10, 20, '')"
            )
            conn.executeSQL(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary, label, sortOrder) " +
                    "VALUES (1, '01012345678', '+201012345678', 'MOBILE', 1, 'شخصي', 0)"
            )
            conn.executeSQL(
                "INSERT INTO arham (contactId, relation, layer, frequency, lastContactedAt, pausedUntil, createdAt) " +
                    "VALUES (1, 'BROTHER', 'INNER', 'WEEKLY', 111, NULL, 5)"
            )
            conn.executeSQL(
                "INSERT INTO contact_dates (id, contactId, type, label, month, day, year, sortOrder) " +
                    "VALUES (1, 1, 'BIRTHDAY', NULL, 3, 21, 1990, 0)"
            )
            // FTS المستقل: صف مبذور مباشرة — يجب أن ينجو حرفاً (الترحيل بلا أي عبارة FTS — §I).
            conn.executeSQL(
                "INSERT INTO contacts_fts (docid, displayName, nickname, company, department, notes, normalizedName) " +
                    "VALUES (1, 'نور الدين', 'نور', 'شركة', 'قسم', 'ملاحظة', 'نور الدين')"
            )
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
        }

        val before = readWitnessSnapshot(tempDbPath)
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_2_3.migrate(conn) }
        val after = readWitnessSnapshot(tempDbPath)

        assertEquals(before, after, "جداول الشاهد وFTS المستقل وتعريفه لم يُمسا حرفاً — الترحيل قاصر على call_events وD4")

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(1L, countRows(conn, "call_events"), "صف المكالمة الإرثي هاجر")
        }
    }

    // ===== §J-6: e164 مكرر بمفاتيح مختلفة — كلا الصفين ينجو (بلا فهرس فريد على e164) =====

    @Test
    fun migration23_duplicateE164DistinctPks_bothSurvive() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "dup_1", "+201000000001", "OUT", 10, 1000L, "CALL_LOG")
            seedLegacyCallEvent(db, "dup_2", "+201000000001", "IN", 20, 2000L, "CALL_LOG")

            MIGRATION_2_3.migrate(db)

            val rows = readAllV3Rows(db)
            assertEquals(listOf("dup_1", "dup_2"), rows.map { it.callLogId }, "كلا الصفين بمفاتيحهما")
            assertTrue(rows.all { it.e164 == "+201000000001" }, "نفس e164 — بلا تهذيب ولا إسقاط")
        } finally {
            db.close()
        }
    }

    // ===== §J-7 (البوابة): الناتج المطابق للمخطط المُصدَّر — فتح إنتاجي بلا أي استثناء تحقق =====

    @Test
    fun migration23_resultingSchema_passesProductionRoomValidation_userVersionStamped3() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_gate_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath) { conn ->
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            seedLegacyCallEvent(conn, "obs_missed", "+201000000003", "MISSED", 0, 3000L, "OBSERVER")
        }

        // ترحيل يدوي على الملف (نفس الاستدعاء الذي تُشغِّله Room) ثم فتح إنتاجي:
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_2_3.migrate(conn) }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            // فتح Room يستقبل الناتج بلا أي استثناء تحقق مخطط — البوابة §G.7-7.
            assertTrue(db.observeContacts().first().isEmpty(), "Room يفتح على الناتج المُرحَّل بلا تحقق فاشل")
        } finally {
            db.closeConnection()
        }

        assertEquals(3, userVersionOfFile(tempDbPath), "Room ختم الإصدار 3 (إعادة تشغيله الذكية على شكل v3 كانت عبوراً صامتاً)")

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(2L, countRows(conn, "call_events"), "لا صف ضاع عبر المسار المزدوج (يدوي ثم فتح Room)")
        }
    }

    // ===== §J-8: عدم الهدم — عدادات كل الجداول القائمة محفوظة حرفاً =====

    @Test
    fun migration23_nonDestructive_allPreExistingRowCountsPreserved() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_counts_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, normalizedName, fileAs) " +
                    "VALUES (1, 'sys_1', 'نور', 'FAMILY', 'نور', '')"
            )
            conn.executeSQL(
                "INSERT INTO contact_phones (contactId, raw, e164) VALUES (1, '01012345678', '+201012345678')"
            )
            conn.executeSQL(
                "INSERT INTO contact_emails (contactId, address, type, label, sortOrder) VALUES (1, 'a@example.com', 'PERSONAL', NULL, 0)"
            )
            conn.executeSQL(
                "INSERT INTO contact_addresses (contactId, street, city, region, postalCode, country, type, sortOrder) " +
                    "VALUES (1, 'شارع 9', NULL, NULL, NULL, NULL, 'HOME', 0)"
            )
            conn.executeSQL(
                "INSERT INTO contact_dates (id, contactId, type, label, month, day, year, sortOrder) " +
                    "VALUES (1, 1, 'BIRTHDAY', NULL, 3, 21, NULL, 0)"
            )
            conn.executeSQL(
                "INSERT INTO contact_custom_fields (contactId, label, value, sortOrder) VALUES (1, 'ملاحظة', 'قيمة', 0)"
            )
            conn.executeSQL("INSERT INTO contact_websites (contactId, url, sortOrder) VALUES (1, 'https://example.com', 0)")
            conn.executeSQL(
                "INSERT INTO arham (contactId, relation, layer, frequency, lastContactedAt, pausedUntil, createdAt) " +
                    "VALUES (1, 'BROTHER', 'INNER', 'WEEKLY', 111, NULL, 5)"
            )
            conn.executeSQL(
                "INSERT INTO services (cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164, " +
                    "visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount, isHot, city, " +
                    "ownerVerified, isSavedByUser) " +
                    "VALUES ('srv_1', 'خدمة', 'prof_1', 'مهنة', 'Profession', '+201000000099', 'PUBLIC', " +
                    "0.5, 2, 0.7, 4.5, 10, 0, 'القاهرة', 1, 0)"
            )
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            seedLegacyCallEvent(conn, "log_in", "+201000000002", "IN", 120, 2000L, "CALL_LOG")
            seedLegacyCallEvent(conn, "obs_missed", "+201000000003", "MISSED", 0, 3000L, "OBSERVER")
            conn.executeSQL(
                "INSERT INTO arham_inbox (id, senderUid, senderName, recipientUid, messageText, direction, status, sentAt) " +
                    "VALUES (1, 'u1', 'مُرسل', 'أنا', 'نص', 'RECEIVED', 'READ', 100)"
            )
            conn.executeSQL(
                "INSERT INTO dispute_logs (id, serviceCloudId, description, status, createdAt, updatedAt, resolvedAt) " +
                    "VALUES (1, 'srv_1', 'وصف', 'RESOLVED', 1, 2, NULL)"
            )
            conn.executeSQL("INSERT INTO contacts_sync_checkpoints (key, value) VALUES ('stamp', '123')")
        }

        val countsBefore = readManagedTableCounts(tempDbPath)
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_2_3.migrate(conn) }
        val countsAfter = readManagedTableCounts(tempDbPath)

        assertEquals(countsBefore, countsAfter, "عدادات كل الجداول القائمة محفوظة — لا صف ضاع ولا تُكرر")
        assertEquals(3L, countsAfter.getValue("call_events"), "صفوف call_events الإرثية الثلاثة كلها انتقلت")

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertTrue(tableExists(conn, "emergency_ringing_sessions"), "D4 أُضيفت")
            assertEquals(0L, countRows(conn, "emergency_ringing_sessions"), "D4 تبدأ فارغة — بلا بذر")
        }
    }

    // ===== §J-9: أمان إعادة التشغيل — migrate ثانية على شكل v3: تخطٍ بلا أثر وبلا فقد =====

    @Test
    fun migration23_rerunOnV3Shape_idempotentNoLoss() = runTest {
        val db = openMemoryDb()
        try {
            createV2CallEvents(db)
            seedLegacyCallEvent(db, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")

            MIGRATION_2_3.migrate(db)
            val afterFirst = readAllV3Rows(db)

            MIGRATION_2_3.migrate(db)

            assertEquals(afterFirst, readAllV3Rows(db), "الصفوف كما هي بعد إعادة التشغيل (تخطي hasV3Shape)")
            assertEquals(v3ExpectedColumns, readColumns(db, "call_events"), "الشكل v3 ثابت")
            assertTrue(tableExists(db, "emergency_ringing_sessions"), "D4 موجودة (IF NOT EXISTS — عبور صامت)")
        } finally {
            db.close()
        }
    }

    // ===== §J-10: فتح إنتاجي مباشر على v2 كاملة — ترحيل ثم قراءة وكتابة عبر الواجهة =====

    @Test
    fun migration23_fullV2Fixture_roomOpensMigrates_andFacadeReadWriteSucceeds() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_open_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath) { conn ->
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            seedLegacyCallEvent(conn, "obs_missed", "+201000000003", "MISSED", 0, 3000L, "OBSERVER")
        }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            // كتابة عبر الواجهة — الكاتب الإرثي (T6) بصورته v3 §H.2:
            db.recordInAppCallAttempt("inapp_1", "+201000000002", 2000L)

            // قراءة عبر الواجهة: صف المهاجر OUT والصف الجديد يظهران، وصف MISSED المهاجر
            // (اتجاهه NULL) يُتخطى بلا اختلاق اتجاه (عقد T6 غير الفارغ).
            val recent = db.getRecentCallEvents(listOf("+201000000001", "+201000000002", "+201000000003"), 50)
            assertEquals(setOf("log_out", "inapp_1"), recent.map { it.callLogId }.toSet(), "المرئي: OUT المهاجر + الجديد؛ MISSED/NULL محجوب")
            assertEquals(CallSource.IN_APP, recent.first { it.callLogId == "inapp_1" }.source)
        } finally {
            db.closeConnection()
        }

        assertEquals(3, userVersionOfFile(tempDbPath), "الفتح الإنتاجي رحّل 2→3 وختم الإصدار")

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(
                listOf(
                    expectedH2Row("inapp_1", "+201000000002", 2000L, "IN_APP", direction = "OUT", durationSec = 0L, missed = false),
                ),
                readAllV3Rows(conn).filter { it.callLogId == "inapp_1" },
                "صف in-app المكتوب عبر الواجهة بصورته v3 §H.2 حرفاً",
            )
        }
    }

    // ===== شيم الاعتماد (§J+): إصدار-صفر بشكل v2 → §H.2 عبر الشيم → Room يختم 3 =====

    @Test
    fun adoption_v2ShapedZeroVersion_callEventsMigratedPerH2_andRoomStampsV3() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_adopt_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            seedLegacyCallEvent(conn, "app_missed", "+201000000002", "MISSED", 0, 2000L, "IN_APP")
        }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertTrue(db.observeContacts().first().isEmpty(), "الشيم اعتمد القاعدة وRoom فتحت بلا استثناء")
        } finally {
            db.closeConnection()
        }

        assertEquals(3, userVersionOfFile(tempDbPath), "الشيم أوصل الشكل v3 ثم Room ختمت إصدارها الحالي")

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(
                listOf(
                    expectedH2Row("app_missed", "+201000000002", 2000L, "IN_APP", direction = null, durationSec = 0L, missed = true),
                    expectedH2Row("log_out", "+201000000001", 1000L, "CALL_LOG", direction = "OUT", durationSec = 45L, missed = false),
                ),
                readAllV3Rows(conn),
                "صفوف الإرث هاجرت §H.2 عبر الشيم (شكل v2 مسموح عند إصدار-صفر — dual-shape)",
            )
            assertEquals(d4ExpectedColumns, readColumns(conn, "emergency_ringing_sessions"), "D4 أُنشئت بالشيم بالصورة الموحدة")
        }
    }

    // ===== شيم الاعتماد (§J+): شكل call_events مجهول → فشل آمن بلا أي مساس =====

    @Test
    fun adoption_callEventsUnknownShape_failsClosed_withoutAnyMutation() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t802_unknown_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            seedLegacyCallEvent(conn, "log_out", "+201000000001", "OUT", 45, 1000L, "CALL_LOG")
            conn.executeSQL("ALTER TABLE call_events ADD COLUMN `weirdColumn` TEXT")
        }

        assertFailsWith<LegacySchemaUnknownException> {
            LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        }

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(7, columnCount(conn, "call_events"), "الشكل المجهول كما هو — بلا مساس (التصنيف رفض قبل أي كتابة)")
            assertFalse(tableExists(conn, "emergency_ringing_sessions"), "لا D4 — الشيم لم يُشغَّل")
            assertEquals(0, userVersionOfConn(conn), "Room لم تُفتح — بلا ختم إصدار")
        }
    }
}
