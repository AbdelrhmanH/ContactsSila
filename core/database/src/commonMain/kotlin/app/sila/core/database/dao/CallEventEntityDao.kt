package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.CallEventEntity
import kotlinx.coroutines.flow.Flow

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

    /** آخر N تفاعلات لأرقام جهة واحدة (T6 — «التفاعلات الأخيرة» في شاشة التفاصيل · Issue #27). */
    @Query("SELECT * FROM call_events WHERE e164 IN (:e164s) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentForE164s(e164s: List<String>, limit: Int): List<CallEventEntity>

    /** مرصد آخر N تفاعلات لأرقام جهة واحدة — تفاعلي عبر InvalidationTracker (T6). */
    @Query("SELECT * FROM call_events WHERE e164 IN (:e164s) ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentForE164s(e164s: List<String>, limit: Int): Flow<List<CallEventEntity>>

    /** يقرأ حداً بمعرفه أو `null` (T8-03 — قراءات إدخال أرحام فوق v3 عبر DAOs المولَّدة). */
    @Query("SELECT * FROM call_events WHERE callLogId = :eventId LIMIT 1")
    suspend fun getById(eventId: String): CallEventEntity?

    /** كل الأحداث ضمن النافذة الشاملة [fromMillis, toMillis] بترتيب زمني تصاعدي (T8-03). */
    @Query("SELECT * FROM call_events WHERE timestamp BETWEEN :fromMillis AND :toMillis ORDER BY timestamp")
    suspend fun findBetween(fromMillis: Long, toMillis: Long): List<CallEventEntity>
}
