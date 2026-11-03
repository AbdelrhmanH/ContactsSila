package app.sila.core.database

/**
 * DDL جداول الحقول القياسية الخمسة (v2 — T6 · Issue #27 · ADR-0012) — **مصدر وحيد**
 * يُستهلك من مسارين يجب أن ينتجَا الشكل الفيزيائي نفسه حرفاً (تحقق Room من المخطط
 * يقارن الأعمدة/الافتراضيات/الفهارس بمطابقة تامة):
 * 1. [MIGRATION_1_2] — ترحيل قواعد Room v1.
 * 2. شيم الاعتماد الآمن (`legacy/LegacyDatabaseAdopter`) — قواعد `user_version = 0`
 *    تتحقق Room من مخططها مباشرةً **ضد كيانات المخطط الحالي** (مسار create/validate —
 *    قرار ADR-0011)، فيجب أن يوصلها الشيم لشكل v2 كاملاً لا شكل v1 التاريخي.
 *
 * **لا تُعدَّل النصوص هنا إلا مع تحديث كيانات Room وتصدير المخطط معاً** — أي انحراف
 * بين هذه النصوص والكيانات يُفشل فتح Room عند الاعتماد (fail-closed بالتصميم).
 */
internal object StandardFieldTablesDdl {

    /** إنشاء الجداول الخمسة — بنصوص مطابقة حرفياً لما يولده مولّد Room من الكيانات. */
    val CREATE_TABLE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `contact_emails` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `contactId` INTEGER NOT NULL,
            `address` TEXT NOT NULL,
            `type` TEXT NOT NULL DEFAULT 'PERSONAL',
            `label` TEXT,
            `sortOrder` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
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
        """.trimIndent(),
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
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `contact_custom_fields` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `contactId` INTEGER NOT NULL,
            `label` TEXT NOT NULL,
            `value` TEXT NOT NULL,
            `sortOrder` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `contact_websites` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `contactId` INTEGER NOT NULL,
            `url` TEXT NOT NULL,
            `sortOrder` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
    )

    /** فهارس الجداول الخمسة — فهرس واحد على `contactId` لكل جدول (كما يولده Room). */
    val CREATE_INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_contact_emails_cid` ON `contact_emails` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_addresses_cid` ON `contact_addresses` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_dates_cid` ON `contact_dates` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_custom_fields_cid` ON `contact_custom_fields` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_websites_cid` ON `contact_websites` (`contactId`)",
    )
}
