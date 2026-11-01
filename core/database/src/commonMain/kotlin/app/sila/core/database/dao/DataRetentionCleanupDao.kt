package app.sila.core.database.dao

/**
 * عقد DAO لتنظيف البيانات منتهية الصلاحية وفق سياسة الاحتفاظ بالبيانات (SECURITY_PRIVACY.md §10).
 *
 * PR B (ADR-0011): عقد نطاق بلا شروح Room — الذرّية تُنفَّذ داخل معاملة واحدة
 * (`withWriteTransaction`) في طبقة data.
 */
interface DataRetentionCleanupDao {
    /**
     * - حذف call_events الأقدم من 365 يوماً.
     * - حذف arham_inbox الأقدم من 30 يوماً.
     * - أرشفة dispute_logs المحلولة الأقدم من 90 يوماً.
     */
    suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport
}
