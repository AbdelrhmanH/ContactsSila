package app.sila.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد Room DAO لبيانات جهات الاتصال المحلية (Single Source of Truth — ARCHITECTURE.md §5).
 * القراءات التفاعلية تُعرض كـ Flow<T>، وعمليات الكتابة معلّقة (suspend).
 */
@Dao
interface ContactDao {

    @Query(
        """
        SELECT id, systemContactId, displayName, firstName, lastName, company, photoUri,
               category, isFavorite, excludeFromSmartFilter, normalizedName, createdAt, updatedAt
        FROM contacts
        WHERE (:category IS NULL OR category = :category)
          AND (:searchQuery = '' OR normalizedName LIKE '%' || :searchQuery || '%')
        ORDER BY displayName ASC
        """
    )
    fun observeContacts(category: Category? = null, searchQuery: String = ""): Flow<List<Contact>>

    @Query(
        """
        SELECT id, systemContactId, displayName, firstName, lastName, company, photoUri,
               category, isFavorite, excludeFromSmartFilter, normalizedName, createdAt, updatedAt
        FROM contacts
        WHERE isFavorite = 1
        ORDER BY displayName ASC
        """
    )
    fun observeFavorites(): Flow<List<Contact>>

    @Query("SELECT category, COUNT(id) FROM contacts GROUP BY category")
    fun observeCategoryCounts(): Flow<Map<Category, Int>>

    @Query(
        """
        SELECT id, systemContactId, displayName, firstName, lastName, company, photoUri,
               category, isFavorite, excludeFromSmartFilter, normalizedName, createdAt, updatedAt
        FROM contacts
        WHERE id = :id
        LIMIT 1
        """
    )
    suspend fun getContactById(id: Long): Contact?

    @Upsert
    suspend fun upsertContact(contact: Contact)

    @Query("UPDATE contacts SET category = :category WHERE id = :contactId")
    suspend fun updateCategory(contactId: Long, category: Category)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContact(contactId: Long)
}
