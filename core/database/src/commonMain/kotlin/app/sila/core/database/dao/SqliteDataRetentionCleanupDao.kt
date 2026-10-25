package app.sila.core.database.dao

/**
 * تطبيق مبدأ المسؤولية الواحدة وفصل الواجهات (SRP & ISP — ARCHITECTURE.md §1):
 * كلاس DAO مخصص حصرياً لتنظيف البيانات منتهية الصلاحية فوق محرك SQLite المحلي.
 */
class SqliteDataRetentionCleanupDao(
    private val db: LocalSilaDatabase
) : DataRetentionCleanupDao by db
