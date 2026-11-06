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

    /**
     * الأحداث التي تأكيداها ما زال `PENDING` حصراً (T8-06 · AC-7) — الأحدث أولاً.
     * الاستعلام لا يغيّر مخطط قاعدة البيانات (عمود `confirmation` قائم منذ v3).
     */
    @Query("SELECT * FROM call_events WHERE confirmation = 'PENDING' ORDER BY timestamp DESC")
    fun observePending(): Flow<List<CallEventEntity>>

    /**
     * مرصد أحداث النافذة الشاملة `[fromMillis, toMillis]` (T8-07 · AC-2 تجميع اللوحة) —
     * الأحدث أولاً. قراءة حصراً فوق العمود المفهرس `idx_call_events_ts`؛ لا تغيير مخطط.
     */
    @Query("SELECT * FROM call_events WHERE timestamp BETWEEN :fromMillis AND :toMillis ORDER BY timestamp DESC")
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEventEntity>>

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
