package app.sila.core.database.dao

/**
 * تطبيق مبدأ المسؤولية الواحدة وفصل الواجهات (SRP & ISP — ARCHITECTURE.md §1):
 * كلاس DAO مخصص حصرياً لعمليات جهات الاتصال فوق محرك SQLite المحلي.
 */
class SqliteContactDao(
    private val db: LocalSilaDatabase
) : ContactDao by db
