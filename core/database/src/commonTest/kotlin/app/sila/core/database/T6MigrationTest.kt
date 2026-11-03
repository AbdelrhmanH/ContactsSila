package app.sila.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات ترحيل المخطط v1→v2 (T6 · Issue #27 · ADR-0011) — على اتصال SQLite خام
 * بشكل v1 الفعلي (الجداول قبل الترحيل، بلا `1.json` مُصدَّر — التصدير بدأ مع v2):
 *
 * 1. إنشاء `contacts` + `contact_phones` **بشكل v1 حرفي** (بلا `label`/`sortOrder`).
 * 2. إدخال بيانات مستخدم قديمة.
 * 3. تنفيذ [MIGRATION_1_2] مباشرة (نفس الاستدعاء الذي تُشغِّله Room عند الافتتاح).
 * 4. التحقق: بيانات المستخدمين محفوظة حرفاً، العمودان الجديدان بقيمهما الافتراضية،
 *    والجداول الخمسة الجديدة تستقبل إدراجاً بأعمدتها الصحيحة.
 */
class T6MigrationTest {

    /** فتح اتصال ذاكرة خام بنفس السائق المجمَّع (BundledSQLiteDriver — نمط الإنتاج). */
    private fun openMemoryDb() = BundledSQLiteDriver().open(":memory:")

    /** إنشاء الجدولين v1 اللذين يمسهما الترحيل — DDL مطابق لما أنشأته Room v1. */
    private suspend fun createV1Tables(db: androidx.sqlite.SQLiteConnection) {
        // جدول جهات الاتصال (عمود `id` وحيد يكفي للترحيل — الجداول الجديدة بلا FK فيزيائي).
        db.executeSQL("CREATE TABLE IF NOT EXISTS `contacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        // جدول الهواتف — **شكل v1**: بلا عمودي `label`/`sortOrder` (أُضيفا في v2).
        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_phones` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `raw` TEXT NOT NULL,
                `e164` TEXT NOT NULL,
                `type` TEXT NOT NULL DEFAULT 'MOBILE',
                `isPrimary` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_phones_cid` ON `contact_phones` (`contactId`)")
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_phones_e164` ON `contact_phones` (`e164`)")
    }

    @Test
    fun migration12_preservesUserPhoneRows_andDefaultsNewColumns() = runTest {
        val db = openMemoryDb()
        try {
            createV1Tables(db)
            db.executeSQL("INSERT INTO `contacts` (`id`) VALUES (7)")
            db.executeSQL(
                "INSERT INTO `contact_phones` (`contactId`, `raw`, `e164`, `type`, `isPrimary`) " +
                    "VALUES (7, '010 1234 5678', '+201012345678', 'MOBILE', 1)"
            )
            db.executeSQL(
                "INSERT INTO `contact_phones` (`contactId`, `raw`, `e164`, `type`, `isPrimary`) " +
                    "VALUES (7, '015 5555 5555', '+20155555555', 'WORK', 0)"
            )

            MIGRATION_1_2.migrate(db)

            // بيانات المستخدم القديمة نجوت حرفاً + القيم الافتراضية للعمودين الجديدين.
            val rows = mutableListOf<Triple<String, String?, Long>>()
            db.prepare("SELECT `raw`, `label`, `sortOrder` FROM `contact_phones` ORDER BY `id`").use { stmt ->
                while (stmt.step()) {
                    rows += Triple(stmt.getText(0), if (stmt.isNull(1)) null else stmt.getText(1), stmt.getLong(2))
                }
            }
            assertEquals(2, rows.size, "صفوف المستخدم القديمة محفوظة حرفاً (غير هدمي)")
            assertEquals(Triple("010 1234 5678", null, 0L), rows[0], "label=NULL وsortOrder=0 الافتراضي")
            assertEquals(Triple("015 5555 5555", null, 0L), rows[1])
        } finally {
            db.close()
        }
    }

    @Test
    fun migration12_createsAllFiveStandardFieldTables_withCorrectColumns() = runTest {
        val db = openMemoryDb()
        try {
            createV1Tables(db)

            MIGRATION_1_2.migrate(db)

            // كل جدول جديد يستقبل إدراجاً بأعمدته الفعلية — فحص الشكل حرفياً.
            db.executeSQL(
                "INSERT INTO `contact_emails` (`contactId`, `address`, `type`, `label`, `sortOrder`) " +
                    "VALUES (7, 'mona@example.com', 'PERSONAL', NULL, 0)"
            )
            db.executeSQL(
                "INSERT INTO `contact_addresses` (`contactId`, `street`, `city`, `region`, `postalCode`, `country`, `type`, `sortOrder`) " +
                    "VALUES (7, 'شارع 9', 'القاهرة', 'القاهرة', '11511', 'مصر', 'HOME', 0)"
            )
            db.executeSQL(
                "INSERT INTO `contact_dates` (`contactId`, `type`, `label`, `month`, `day`, `year`, `sortOrder`) " +
                    "VALUES (7, 'BIRTHDAY', NULL, 3, 21, NULL, 0)"
            )
            db.executeSQL(
                "INSERT INTO `contact_custom_fields` (`contactId`, `label`, `value`, `sortOrder`) " +
                    "VALUES (7, 'المهنة', 'مهندسة', 0)"
            )
            db.executeSQL("INSERT INTO `contact_websites` (`contactId`, `url`, `sortOrder`) VALUES (7, 'https://example.com', 0)")

            // الجداول موجودة ومأهولة بالفعل (بعد الترحيل لا قبلها).
            for (table in listOf("contact_emails", "contact_addresses", "contact_dates", "contact_custom_fields", "contact_websites")) {
                val count = db.prepare("SELECT COUNT(*) FROM `$table`").use { stmt ->
                    assertTrue(stmt.step(), "الجدول $table يجب أن يوجد بعد الترحيل")
                    stmt.getLong(0)
                }
                assertEquals(1, count, "الجدول $table يستقبل صفوفاً بعد الترحيل")
            }

            // التوقيت: قبل الترحيل الجداول غير موجودة (اختبار الاتجاه — الترحيل هو من أنشأها).
        } finally {
            db.close()
        }
    }

    @Test
    fun migration12_tablesDidNotExist_beforeMigration() = runTest {
        val db = openMemoryDb()
        try {
            createV1Tables(db)

            val beforeCount = countUserTables(db)
            assertEquals(2, beforeCount, "v1: جهات + هواتف حصراً في نطاق هذا الاختبار")

            MIGRATION_1_2.migrate(db)

            assertEquals(7, countUserTables(db), "v2: خمسة جداول حقول قياسية أُضيفت")
        } finally {
            db.close()
        }
    }

    /** عدد جداول المستخدم (بلا جداول SQLite الداخلية/الفهارس) عبر sqlite_master. */
    private suspend fun countUserTables(db: androidx.sqlite.SQLiteConnection): Int {
        var count = 0L
        db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { stmt ->
            if (stmt.step()) count = stmt.getLong(0)
        }
        return count.toInt()
    }
}
