package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة قائمة جهات الاتصال مع التصفية والبحث (SRP).
 */
class ObserveContactsUseCase(
    private val repository: ContactsRepository
) {
    operator fun invoke(category: Category? = null, searchQuery: String = ""): Flow<List<Contact>> =
        repository.observeContacts(category = category, searchQuery = searchQuery)
}
