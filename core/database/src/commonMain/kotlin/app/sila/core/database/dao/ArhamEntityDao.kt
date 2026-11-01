package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ArhamEntity

/**
 * DAO كيانات صلة الرحم (أرحام) فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface ArhamEntityDao {

    @Query("SELECT * FROM arham")
    suspend fun getAll(): List<ArhamEntity>

    @Query("SELECT * FROM arham WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: Long): ArhamEntity?

    @Upsert
    suspend fun upsert(entity: ArhamEntity)

    @Query("UPDATE arham SET lastContactedAt = :timestampMillis WHERE contactId = :contactId")
    suspend fun recordContactCall(contactId: Long, timestampMillis: Long)

    /** حذف صف أرحام (يُستدعى داخل معاملة حذف جهة — تكامل تديره طبقة البيانات). */
    @Query("DELETE FROM arham WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: Long)
}
