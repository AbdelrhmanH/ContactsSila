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

    override fun searchContacts(query: String): Flow<List<Contact>> =
        contactDao.searchContacts(query)

    override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {
        contactDao.setFavorite(contactId, isFavorite)
    }

    override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {
        contactDao.updateCategories(contactIds, category)
    }

    override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {
        contactDao.setFavorites(contactIds, isFavorite)
    }

    override suspend fun deleteContacts(contactIds: Collection<Long>) {
        contactDao.deleteContacts(contactIds)
    }

    override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) {
        contactDao.recordInAppCallAttempt(
            callLogId = callLogId,
            e164 = e164,
            timestampMillis = timestampMillis
        )
    }
}
