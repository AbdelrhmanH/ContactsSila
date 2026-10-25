package app.sila.core.database.dao

/**
 * تطبيق مبدأ المسؤولية الواحدة وفصل الواجهات (SRP & ISP — ARCHITECTURE.md §1):
 * كلاس DAO مخصص حصرياً لعمليات صلة الرحم (أرحام) فوق محرك SQLite المحلي.
 */
class SqliteArhamDao(
    private val db: LocalSilaDatabase
) : ArhamDao by db
