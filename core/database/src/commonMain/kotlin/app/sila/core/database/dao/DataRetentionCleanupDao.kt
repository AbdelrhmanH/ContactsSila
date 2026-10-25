package app.sila.core.database.dao

import androidx.room.Dao
import androidx.room.Transaction

/**
 * عقد Room DAO لتنظيف البيانات منتهية الصلاحية وفق سياسة الاحتفاظ بالبيانات (SECURITY_PRIVACY.md §10).
 */
@Dao
interface DataRetentionCleanupDao {
    /**
     * - حذف call_events الأقدم من 365 يوماً.
     * - حذف arham_inbox الأقدم من 30 يوماً.
     * - أرشفة dispute_logs المحلولة الأقدم من 90 يوماً.
     */
    @Transaction
    suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport
}
