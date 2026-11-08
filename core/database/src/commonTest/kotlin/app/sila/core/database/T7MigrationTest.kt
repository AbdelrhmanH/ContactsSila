package app.sila.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.async.executeSQL
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.legacy.PlatformFileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ترحيل جداول الدمج الثلاثة v3→v4 (T7 · Issue #28 · agy F1) — نمط [T8MigrationTest]:
 * اتصال خام بشكل v3 (قطعة v2 كاملة + [MIGRATION_2_3])، ترحيل [MIGRATION_3_4] يدوياً،
 * تحقق الشكل الفيزيائي + فتح Room الإنتاجي ضد الكيانات الحالية.
 */
class T7MigrationTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun migration34_createsThreeMergeTables_withExactShapes_andNonDestructive() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t7_mig_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, normalizedName, fileAs) " +
                    "VALUES (1, 'sys_t7', 'نور', 'FAMILY', 'نور', '')",
            )
        }
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_2_3.migrate(conn) }
        val preExistingContacts = BundledSQLiteDriver().open(tempDbPath).use { conn ->
            conn.prepare("SELECT COUNT(*) FROM contacts").use { it.step(); it.getLong(0)!! }
        }

        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_3_4.migrate(conn) }

        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            assertEquals(preExistingContacts, 1L, "عدم الهدم: صف contacts القائم باقٍ حرفاً")
            for (table in listOf("contact_merge_snapshots", "duplicate_dismissals", "smart_filter_feedback")) {
                assertEquals(0L, countRows(conn, table), "الجدول $table أُنشئ فارغاً (قيد مخفَّض سابق)")
            }
            // فهارس الجداول الثلاثة بالأسماء الموحدة مع [MergeTablesDdl]
            for (index in listOf(
                "idx_contact_merge_snapshots_groupId",
                "idx_contact_merge_snapshots_createdAt",
                "idx_duplicate_dismissals_groupKey",
                "idx_smart_filter_feedback_token",
            )) {
                assertEquals(1L, countRows(conn, "sqlite_master", "type = 'index' AND name = '$index'"), "فهرس $index موجود")
            }
            // المفتاح المركب لـ smart_filter_feedback (agy F1) — autoindex بأصل pk وعمودين
            val pkColumns = mutableListOf<String>()
            conn.prepare("PRAGMA index_list(`smart_filter_feedback`)").use { stmt ->
                while (stmt.step()) {
                    if (stmt.getText(3) == "pk") {
                        val idxName = stmt.getText(1)
                        conn.prepare("PRAGMA index_info(`$idxName`)").use { info ->
                            while (info.step()) pkColumns.add(info.getText(2))
                        }
                    }
                }
            }
            assertEquals(listOf("token", "suggestedCategory"), pkColumns, "PK مركب حتمي (token, suggestedCategory)")
            // FTS غير مُماس: جدول contacts_fts كما هو
            assertEquals(1L, countRows(conn, "sqlite_master", "type = 'table' AND name = 'contacts_fts'"))
        }
    }

    @Test
    fun migration34_thenProductionRoomOpen_validatesAndStampsCurrentVersion() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_t7_open_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createFullRoomV2Database(tempDbPath)
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_2_3.migrate(conn) }
        BundledSQLiteDriver().open(tempDbPath).use { conn -> MIGRATION_3_4.migrate(conn) }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertTrue(db.observeContacts().first().isEmpty(), "Room يفتح على الناتج v4 بلا أي استثناء تحقق مخطط")
        } finally {
            db.closeConnection()
        }
        assertEquals(SILA_ROOM_VERSION, userVersionOfFile(tempDbPath), "Room ختمت الإصدار الحالي على الناتج المُرحَّل يدوياً")
    }

    private fun countRows(conn: androidx.sqlite.SQLiteConnection, table: String, where: String? = null): Long =
        conn.prepare("SELECT COUNT(*) FROM `$table`${if (where != null) " WHERE $where" else ""}").use { stmt ->
            stmt.step()
            stmt.getLong(0)!!
        }

    private suspend fun userVersionOfFile(path: String): Int =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("PRAGMA user_version").use { stmt ->
                stmt.step()
                stmt.getInt(0)
            }
        }
}
