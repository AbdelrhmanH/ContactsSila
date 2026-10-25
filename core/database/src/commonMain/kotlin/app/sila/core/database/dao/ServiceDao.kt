package app.sila.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.sila.core.model.Service
import kotlinx.coroutines.flow.Flow

/**
 * عقد Room DAO لبيانات الخدمات المحلية بنمط Offline-First (ARCHITECTURE.md §5).
 */
@Dao
interface ServiceDao {

    @Query(
        """
        SELECT cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164,
               visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount,
               isHot, city, ownerVerified, isSavedByUser
        FROM services
        WHERE (:professionId IS NULL OR professionId = :professionId)
          AND (:searchQuery = '' OR name LIKE '%' || :searchQuery || '%')
        ORDER BY trustScore DESC
        """
    )
    fun observeServices(professionId: String? = null, searchQuery: String = ""): Flow<List<Service>>

    @Upsert
    suspend fun upsertServices(services: List<Service>)

    @Query(
        """
        SELECT cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164,
               visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount,
               isHot, city, ownerVerified, isSavedByUser
        FROM services
        WHERE phoneE164 = :e164
        LIMIT 1
        """
    )
    suspend fun findByPhoneE164(e164: String): Service?
}
