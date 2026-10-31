package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع جهات الاتصال (:feature:contacts:domain) — Dependency Inversion (DIP).
 */
interface ContactsRepository {
    fun observeContacts(category: Category? = null, searchQuery: String = ""): Flow<List<Contact>>
    fun observeFavorites(): Flow<List<Contact>>
    fun observeCategoryCounts(): Flow<Map<Category, Int>>
    suspend fun getContactById(id: Long): Contact?
    suspend fun upsertContact(contact: Contact)
    suspend fun updateCategory(contactId: Long, category: Category)
    suspend fun deleteContact(contactId: Long)

    /** البحث الإنتاجي عبر FTS4 + أجزاء الهواتف (Issue #24) — يستبعد القبور دائماً. */
    fun searchContacts(query: String): Flow<List<Contact>>

    /** تبديل مفضلة جهة واحدة (عمود صِلَة). */
    suspend fun setFavorite(contactId: Long, isFavorite: Boolean)

    /** تصنيف دفعة واحدة (اختيار متعدد — Issue #24). */
    suspend fun updateCategories(contactIds: Collection<Long>, category: Category)

    /** مفضلة دفعة واحدة (اختيار متعدد — Issue #24). */
    suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean)

    /** حذف فعلي دفعة واحدة (بعد تأكيد المستخدم — Issue #24). */
    suspend fun deleteContacts(contactIds: Collection<Long>)

    /** تسجيل محاولة اتصال `IN_APP` محلياً (ADR-0006 — بلا أذونات سجل مكالمات). */
    suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long)
}
