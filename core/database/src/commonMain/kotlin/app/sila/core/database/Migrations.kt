package app.sila.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL

/**
 * ترحيلات Room الرسمية (ADR-0011 — صاحب مخطط وحيد، لا fallback هدمي).
 *
 * ## MIGRATION_1_2 — T6 (Issue #27): جداول الحقول القياسية متعددة القيم
 *
 * الجداول المضافة (أعمدة كل جدول تطابق كيان Room المُصرَّح حرفياً — التحقق عبر
 * `MigrationTestHelper` على مخططي 1.json/2.json):
 * - `contact_emails` — بريد جهة الاتصال (معكوس من المنصة — ADR-0012).
 * - `contact_addresses` — العناوين البريدية (صِلَة-فقط — ADR-0012).
 * - `contact_dates` — التواريخ الهامة/الميلاد (صِلَة-فقط؛ أساس اشتقاق مناسبات أرحام — J-4).
 * - `contact_custom_fields` — الحقول المخصصة (صِلَة-فقط — قرار المواصفة 03: Room لا MIME).
 * - `contact_websites` — المواقع/الروابط (معكوس من المنصة — ADR-0012).
 *
 * وتوسعة `contact_phones` بعمودين غير هدميين (AC 8 «تُسمَّى وتُرتَّب»):
 * - `label TEXT` — تسمية مخصصة (صِلَة-فقط).
 * - `sortOrder INTEGER NOT NULL DEFAULT 0` — ترتيب داخل المجموعة.
 *
 * **غير هدمي حصراً:** `CREATE TABLE` + `ALTER TABLE ADD COLUMN` — لا إعادة بناء ولا فقد
 * بيانات؛ جداول FTS لا تُمس (لا أعمدة جديدة فيها — ADR-0011 §FTS4). بلا قيود FK فيزيائية
 * (توثيقية فقط — ADR-0011 · Decision 5).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_emails` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `address` TEXT NOT NULL,
                `type` TEXT NOT NULL DEFAULT 'PERSONAL',
                `label` TEXT,
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_emails_cid` ON `contact_emails` (`contactId`)")

        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_addresses` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `street` TEXT,
                `city` TEXT,
                `region` TEXT,
                `postalCode` TEXT,
                `country` TEXT,
                `type` TEXT NOT NULL DEFAULT 'HOME',
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_addresses_cid` ON `contact_addresses` (`contactId`)")

        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_dates` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `label` TEXT,
                `month` INTEGER NOT NULL,
                `day` INTEGER NOT NULL,
                `year` INTEGER,
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_dates_cid` ON `contact_dates` (`contactId`)")

        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_custom_fields` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `label` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_custom_fields_cid` ON `contact_custom_fields` (`contactId`)")

        db.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS `contact_websites` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `url` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.executeSQL("CREATE INDEX IF NOT EXISTS `idx_contact_websites_cid` ON `contact_websites` (`contactId`)")

        // توسعة غير هدمية لجدول الهواتف القائم (صفوف المستخدمين محفوظة حرفياً).
        db.executeSQL("ALTER TABLE `contact_phones` ADD COLUMN `label` TEXT")
        db.executeSQL("ALTER TABLE `contact_phones` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
    }
}

/** كل الترحيلات الرسمية بالترتيب — تُسجَّل على منشئ قاعدة Room وحيداً. */
val SILA_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
