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

    /**
     * البحث الإنتاجي عبر فهرس FTS4 + جزء من رقم الهاتف (Issue #24 · T3):
     * يُطابق `displayName`/`normalizedName`/`nickname`/`company`/`department`/`notes`
     * عبر `contacts_fts MATCH` (معامل مُقيَّد لا سلسلة مُسلسَلة)، وأي جزء من الرقم عبر
     * `contact_phones`، ويستبعد القبور (`systemDeletedAt IS NULL`) دائماً.
     * يعاد رصد التحديثات لأنه يُبنى فوق كاش الذاكرة الذي تُحدّثه معاملات الكتابة/المزامنة.
     */
    fun searchContacts(query: String): Flow<List<Contact>>

    /** تبديل المفضلة لجهة واحدة (عمود صِلَة — لا يلمسه أبداً عكس المنصة ADR-0010 §7). */
    suspend fun setFavorite(contactId: Long, isFavorite: Boolean)

    /** تصنيف دفعة واحدة ذرّية لعدة جهات (اختيار متعدد — Issue #24). */
    suspend fun updateCategories(contactIds: Collection<Long>, category: Category)

    /** مفضلة دفعة واحدة ذرّية لعدة جهات (اختيار متعدد — Issue #24). */
    suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean)

    /** حذف فعلي دفعة واحدة لعدة جهات (اختيار متعدد مع تأكيد — Issue #24). */
    suspend fun deleteContacts(contactIds: Collection<Long>)

    /**
     * تسجيل محاولة اتصال من داخل التطبيق (ADR-0006: أفعال الاتصال من القائمة تسجّل
     * `IN_APP` محلياً — بلا `READ_CALL_LOG` وبلا أي وصول شبكي).
     */
    suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long)
}
