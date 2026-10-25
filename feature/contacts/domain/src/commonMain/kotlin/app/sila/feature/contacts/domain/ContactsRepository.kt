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
}
