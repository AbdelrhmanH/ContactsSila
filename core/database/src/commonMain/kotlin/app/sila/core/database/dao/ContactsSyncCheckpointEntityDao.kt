package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ContactsSyncCheckpointEntity

/**
 * DAO كيانات نقاط استئناف المزامنة فوق Room 3 (طبقة data حصراً — ADR-0011).
 *
 * مفتاح/قيمة معتمة — فك/تغليف اللقطة مسؤولية `:feature:contacts:data` (ADR-0010 §4/§8).
 */
@Dao
interface ContactsSyncCheckpointEntityDao {

    @Query("SELECT value FROM contacts_sync_checkpoints WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Query("SELECT key, value FROM contacts_sync_checkpoints")
    suspend fun getAll(): List<ContactsSyncCheckpointEntity>

    @Upsert
    suspend fun upsert(entity: ContactsSyncCheckpointEntity)

    @Query("DELETE FROM contacts_sync_checkpoints WHERE key = :key")
    suspend fun delete(key: String)
}
