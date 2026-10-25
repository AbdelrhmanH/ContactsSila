package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة جهات الاتصال المفضلة (SRP).
 */
class ObserveFavoriteContactsUseCase(
    private val repository: ContactsRepository
) {
    operator fun invoke(): Flow<List<Contact>> = repository.observeFavorites()
}
