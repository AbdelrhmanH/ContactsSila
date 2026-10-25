package app.sila.core.database.dao

/**
 * تطبيق مبدأ المسؤولية الواحدة وفصل الواجهات (SRP & ISP — ARCHITECTURE.md §1):
 * كلاس DAO مخصص حصرياً لعمليات دليل الخدمات فوق محرك SQLite المحلي.
 */
class SqliteServiceDao(
    private val db: LocalSilaDatabase
) : ServiceDao by db
