package app.sila.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد Room DAO لبيانات صلة الرحم (أرحام) المحلية (ARCHITECTURE.md §5).
 */
@Dao
interface ArhamDao {

    @Query(
        """
        SELECT c.id, c.systemContactId, c.displayName, c.firstName, c.lastName, c.company, c.photoUri,
               c.category, c.isFavorite, c.excludeFromSmartFilter, c.normalizedName, c.createdAt, c.updatedAt
        FROM contacts c
        INNER JOIN arham a ON a.contactId = c.id
        WHERE c.category = 'FAMILY'
        """
    )
    fun observeArhamContacts(): Flow<List<Contact>>

    @Upsert
    suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo)

    @Query("UPDATE arham SET lastContactedAt = :timestampMillis WHERE contactId = :contactId")
    suspend fun recordContactCall(contactId: Long, timestampMillis: Long)
}
