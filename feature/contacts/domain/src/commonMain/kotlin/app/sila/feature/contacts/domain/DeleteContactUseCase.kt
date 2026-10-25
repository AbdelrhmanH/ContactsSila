package app.sila.feature.contacts.domain

/**
 * حالة استخدام أحادية المسؤولية: حذف جهة اتصال محلياً (SRP).
 */
class DeleteContactUseCase(
    private val repository: ContactsRepository
) {
    suspend operator fun invoke(contactId: Long) {
        repository.deleteContact(contactId)
    }
}
