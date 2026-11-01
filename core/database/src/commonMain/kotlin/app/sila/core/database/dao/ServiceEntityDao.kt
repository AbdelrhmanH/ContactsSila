package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ServiceEntity

/**
 * DAO كيانات الخدمات فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface ServiceEntityDao {

    @Query("SELECT * FROM services")
    suspend fun getAll(): List<ServiceEntity>

    @Query("SELECT * FROM services WHERE phoneE164 = :e164 LIMIT 1")
    suspend fun findByPhoneE164(e164: String): ServiceEntity?

    @Upsert
    suspend fun upsertAll(services: List<ServiceEntity>)

    @Upsert
    suspend fun upsert(service: ServiceEntity)
}
