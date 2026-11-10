package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.DisputeLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO كيانات سجل النزاعات فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface DisputeLogEntityDao {

    @Insert
    suspend fun insert(dispute: DisputeLogEntity): Long

    @Upsert
    suspend fun upsertAll(disputes: List<DisputeLogEntity>)

    @Query("SELECT COUNT(*) FROM dispute_logs WHERE status = 'RESOLVED' AND resolvedAt IS NOT NULL AND resolvedAt < :cutoff")
    suspend fun countResolvedOlderThan(cutoff: Long): Int

    @Query(
        "UPDATE dispute_logs SET status = 'ARCHIVED', updatedAt = :nowMillis " +
            "WHERE status = 'RESOLVED' AND resolvedAt IS NOT NULL AND resolvedAt < :cutoff"
    )
    suspend fun archiveResolvedOlderThan(cutoff: Long, nowMillis: Long): Int

    /**
     * قراءة SSOT حصراً (S1-5 — تذكرة #61): استعلام مرتّب تنازلياً على `updatedAt`
     * ثم على `createdAt` لكسر التعادل — استعلام قراءة مستقل كلياً عن مسار الكتابة
     * الذي بقي خارج المرحلة 1 («توثيق نزاع» — المرحلة 2). يستهلكه مسار التصدير في
     * `SettingsRepositoryImpl` بدل القائمة المدوّنة الفارغة، ولائحة «نزاعاتي»
     * على المنصتين ستستهلكه لاحقاً عند جهوز الكاتب.
     */
    @Query("SELECT * FROM dispute_logs ORDER BY updatedAt DESC, createdAt DESC")
    fun observeDisputeLogsOrdered(): Flow<List<DisputeLogEntity>>
}
