package app.sila.feature.contacts.data

import app.sila.core.database.dao.ContactDao
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.feature.contacts.domain.ContactsRepository
import kotlinx.coroutines.flow.Flow

/**
 * تنفيذ مستودع جهات الاتصال (:feature:contacts:data).
 * يعتمد حصرياً على ContactDao المحلي (Single Source of Truth) دون أي اتصال شبكي.
 */
class ContactsRepositoryImpl(
    private val contactDao: ContactDao
) : ContactsRepository {

    override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> =
        contactDao.observeContacts(category = category, searchQuery = searchQuery)

    override fun observeFavorites(): Flow<List<Contact>> =
        contactDao.observeFavorites()

    override fun observeCategoryCounts(): Flow<Map<Category, Int>> =
        contactDao.observeCategoryCounts()

    override suspend fun getContactById(id: Long): Contact? =
        contactDao.getContactById(id)

    override suspend fun upsertContact(contact: Contact) {
        contactDao.upsertContact(contact)
    }

    override suspend fun updateCategory(contactId: Long, category: Category) {
        contactDao.updateCategory(contactId, category)
    }

    override suspend fun deleteContact(contactId: Long) {
        contactDao.deleteContact(contactId)
    }
}
