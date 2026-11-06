package app.sila.core.database.dao

/**
 * تقرير نتائج عملية التنظيف الدوري لقاعدة البيانات المحلية.
 */
data class CleanupReport(
    val deletedCallEvents: Int,
    val deletedInboxMessages: Int,
    val archivedDisputes: Int,
    /** جهات اتصال مُقبرة (systemDeletedAt) تجاوزت 30 يوماً فحُذفت نهائياً (Issue #23 · ADR-0010 §6). */
    val deletedTombstonedContacts: Int = 0,
    /** جلسات طوارئ منتهية (48 ساعة من آخر محاولة، الجارية IN_PROGRESS لا تُمس) حُذفت نهائياً (v3 — T8-02 · قرار D4). */
    val deletedEmergencySessions: Int = 0
)
