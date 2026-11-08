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

/**
 * ## MIGRATION_2_3 — T8 (Issue #29): توسعة `call_events` لحقول ADR-0006 + جدول جلسات الطوارئ
 *
 * SQLite لا يُغيّر لفوية الأعمدة — التوسعة الفردية غير ممكنة، فالتسلسل المُصادق هو
 * **إعادة بناء ونسخ** (§I من تقرير بوابة T8-02، بلا أي اختصار هدمي):
 * 1. تحقق fail-closed من نصوص التعداد الإرثية في `call_events` (قبل أي كتابة — §H.4).
 * 2. إنشاء `call_events_v3_rebuild` بصورة v3 من [CallEventsV3Ddl] (مصدر وحيد مع الشيم).
 * 3. نسخ كل الصفوف بتعبيرات §H.2 حرفاً (محفوظ حرفاً + محولات موثقة + NULL للجديد).
 * 4. إسقاط `call_events` القديم، إعادة تسمية المؤقت، ثم إنشاء فهرس `idx_call_events_ts`
 *    الوحيد بعد إعادة التسمية.
 * 5. إنشاء `emergency_ringing_sessions` (قرار D4 المُصادق — §W).
 *
 * الذرية: الترحيل يعمل داخل معاملة Room الحصرية (تحقق فعلي بالبايت-كود لـ Room 3.0.3:
 * `BaseRoomConnectionManager.configureDatabase` يقرأ `user_version` ثم يغلّف مسار
 * الترحيل بـ `BEGIN EXCLUSIVE TRANSACTION` … `END TRANSACTION` مع `ROLLBACK` عند
 * الفشل) — أي استثناء يُرجع القاعدة كاملة إلى v2 ولا تُفتح Room. لذلك **لا معاملة
 * داخلية هنا** (BEGIN متداخل يرمي).
 *
 * صفوف الإرث المهاجرة كلها UNANSWERED/UNKNOWN + NOT_REQUIRED + LOW → لا يمكن أبداً
 * أن تؤهل لتحديث `lastContactedAt` بقاعدة AC-8 (§H.5 — لا يصنع الترحيل دليلاً مؤكداً).
 * لا يُلمس أي جدول آخر حرفاً — `contacts_fts` على وجه الحصر بلا أي عبارة.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override suspend fun migrate(db: SQLiteConnection) {
        CallEventsV3Ddl.rebuildCallEventsToV3AndCreateEmergencySessionsTable(db)
    }
}

/**
 * ## MIGRATION_3_4 — T7 (Issue #28): جداول الدمج الثلاثة
 *
 * **غير هدمي حصراً**: `CREATE TABLE IF NOT EXISTS` من [MergeTablesDdl] (مصدر وحيد مع الشيم
 * — نفس عهد `StandardFieldTablesDdl`/`CallEventsV3Ddl`) — بلا إسقاطات ولا نسخ بيانات،
 * وFTS غير مُماس إطلاقاً. الجداول فارغة عند الترحيل بالتعريف (قيد مخطط مخفَّض سابق —
 * DATA_MODEL §1 وسمها «تُضاف مع كيانات Room وترحيلات عند تنفيذ ميزاتها» وهذا ترحيلها).
 *
 * الذرية: نفس عهد [MIGRATION_2_3] — Room يغلّف الترحيل بمعاملة حصرية، فلا معاملة داخلية.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        MergeTablesDdl.CREATE_TABLE_STATEMENTS.forEach { connection.executeSQL(it) }
        MergeTablesDdl.CREATE_INDEX_STATEMENTS.forEach { connection.executeSQL(it) }
    }
}

/** كل الترحيلات الرسمية بالترتيب — تُسجَّل على منشئ قاعدة Room وحيداً. */
val SILA_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
