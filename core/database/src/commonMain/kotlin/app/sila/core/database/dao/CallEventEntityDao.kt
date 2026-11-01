package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.CallEventEntity

/**
 * DAO كيانات سجل أحداث المكالمات فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface CallEventEntityDao {

    @Upsert
    suspend fun upsert(event: CallEventEntity)

    @Upsert
    suspend fun upsertAll(events: List<CallEventEntity>)

    @Query("SELECT COUNT(*) FROM call_events WHERE timestamp < :cutoff")
    suspend fun countOlderThan(cutoff: Long): Int

    @Query("DELETE FROM call_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
