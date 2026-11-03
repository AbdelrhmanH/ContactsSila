package app.sila.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL

/**
 * ترحيلات Room الرسمية (ADR-0011 — صاحب مخطط وحيد، لا fallback هدمي).
 *
 * ## MIGRATION_1_2 — T6 (Issue #27): جداول الحقول القياسية متعددة القيم
 *
 * الجداول المضافة (أعمدة كل جدول تطابق كيان Room المُصرَّح حرفياً — DDL من مصدر وحيد
 * [StandardFieldTablesDdl]، والتحقق الفعلي عبر اختبار [MIGRATION_1_2] المباشر على اتصال
 * خام بشكل v1 — `core/database/src/commonTest/.../T6MigrationTest.kt` — إذ لا يوجد
 * تصدير مخطط 1.json):
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
 * **غير هدمي حصراً**: `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN` — بلا
 * إسقاطات ولا نسخ بيانات، وFTS غير مُماس إطلاقاً. نفس الجداول/الأعمدة تُنشئها كذلك
 * شيم الاعتماد الآمن للقواعد `user_version = 0` (الذي يوصلها لشكل المخطط الحالي كاملاً
 * — انظر [StandardFieldTablesDdl]) — المساران ينتجان الشكل الفيزيائي نفسه حرفاً.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override suspend fun migrate(db: SQLiteConnection) {
        StandardFieldTablesDdl.CREATE_TABLE_STATEMENTS.forEach { db.executeSQL(it) }
        StandardFieldTablesDdl.CREATE_INDEX_STATEMENTS.forEach { db.executeSQL(it) }

        // توسعة غير هدمية لجدول الهواتف القائم (صفوف المستخدمين محفوظة حرفياً).
        db.executeSQL("ALTER TABLE `contact_phones` ADD COLUMN `label` TEXT")
        db.executeSQL("ALTER TABLE `contact_phones` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
    }
}

/** كل الترحيلات الرسمية بالترتيب — تُسجَّل على منشئ قاعدة Room وحيداً. */
val SILA_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
