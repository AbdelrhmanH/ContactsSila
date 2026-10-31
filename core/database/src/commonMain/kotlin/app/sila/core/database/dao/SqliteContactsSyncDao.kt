package app.sila.core.database.dao

/**
 * تفويض رقيق لواجهة [ContactsSyncDao] (SRP & ISP — نفس نمط SqliteContactDao):
 * التنفيذ الفعلي في [LocalSilaDatabase]، وهذا الغلاف يوفر نقطة حقن مستقلة للـ DAO.
 */
class SqliteContactsSyncDao(
    private val db: LocalSilaDatabase
) : ContactsSyncDao by db
