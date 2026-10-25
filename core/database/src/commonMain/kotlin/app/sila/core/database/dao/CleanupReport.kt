package app.sila.core.database.dao

/**
 * تقرير نتائج عملية التنظيف الدوري لقاعدة البيانات المحلية.
 */
data class CleanupReport(
    val deletedCallEvents: Int,
    val deletedInboxMessages: Int,
    val archivedDisputes: Int
)
