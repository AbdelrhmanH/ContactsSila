package app.sila.core.database

/**
 * DDL جداول الدمج الثلاثة (v4) — **مصدر وحيد** يُستهلك من مسارين يجب أن ينتجَا الشكل
 * الفيزيائي نفسه حرفاً (تحقق Room من المخطط يقارن الأعمدة/الافتراضيات/الفهارس بمطابقة
 * تامة عبر `TableInfo`):
 * 1. [MIGRATION_3_4] — ترحيل قواعد Room v3 (غير هدمي حصراً: `CREATE TABLE IF NOT EXISTS`
 *    — بلا إسقاطات ولا نسخ بيانات، وFTS غير مُماس إطلاقاً).
 * 2. شيم الاعتماد الآمن (`legacy/LegacyDatabaseAdopter` + `LegacySchemaFingerprint`) —
 *    قواعد `user_version = 0` تتحقق Room من مخططها مباشرةً **ضد كيانات المخطط الحالي**
 *    (مسار create/validate — ADR-0011)، فيجب أن يوصلها الشيم لشكل v4 كاملاً.
 *
 * **النصوص منسوخة حرفاً من `schemas/app.sila.core.database.SilaRoomDatabase/4.json` المولَّد
 * من كيانات Room** — أي تعديل على الكيانات يستلزم إعادة توليد المخطط وتحديث هذه النصوص
 * معاً (fail-closed بالتصميم — نفس عهد `CallEventsV3Ddl`).
 *
 * - `contact_merge_snapshots`: لقطة ما-قبل-الدمج القفلية (ACTIVE→RESTORED/EXPIRED —
 *   استرجاع لمرة-واحدة) — ADR-0004 وspec 04 §أ «لقطة محفوظة في Room».
 * - `duplicate_dismissals`: أزواج «ليست مكررات» بمفتاح هوية ثابت — محجوز للميزة في
 *   ADR-0012 §9 حصراً.
 * - `smart_filter_feedback`: التعلّم المحلي (token→category) بمفتاح مركب حتمي يوفر
 *   التفرد طبيعياً (agy F1).
 */
internal object MergeTablesDdl {

    val CREATE_TABLE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `contact_merge_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `contactCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'ACTIVE', `restoredAt` INTEGER, `expiresAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `duplicate_dismissals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupKey` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `smart_filter_feedback` (`token` TEXT NOT NULL, `suggestedCategory` TEXT NOT NULL, `acceptedCount` INTEGER NOT NULL DEFAULT 0, `rejectedCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`token`, `suggestedCategory`))",
    )

    val CREATE_INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_contact_merge_snapshots_groupId` ON `contact_merge_snapshots` (`groupId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_merge_snapshots_createdAt` ON `contact_merge_snapshots` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `idx_duplicate_dismissals_groupKey` ON `duplicate_dismissals` (`groupKey`)",
        "CREATE INDEX IF NOT EXISTS `idx_smart_filter_feedback_token` ON `smart_filter_feedback` (`token`)",
    )
}
