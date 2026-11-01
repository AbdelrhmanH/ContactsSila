package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import app.sila.core.database.entity.ArhamInboxEntity

/**
 * DAO كيانات صندوق رسائل أرحام فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface ArhamInboxEntityDao {

    @Insert
    suspend fun insertAll(messages: List<ArhamInboxEntity>)

    @Query("SELECT * FROM arham_inbox ORDER BY sentAt ASC")
    suspend fun getAll(): List<ArhamInboxEntity>

    @Query("SELECT COUNT(*) FROM arham_inbox WHERE sentAt < :cutoff")
    suspend fun countOlderThan(cutoff: Long): Int

    @Query("DELETE FROM arham_inbox WHERE sentAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
