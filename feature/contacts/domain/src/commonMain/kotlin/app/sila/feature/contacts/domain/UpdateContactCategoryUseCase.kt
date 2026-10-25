package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * حالة استخدام أحادية المسؤولية: تحديث تصنيف جهة اتصال (SRP).
 */
class UpdateContactCategoryUseCase(
    private val repository: ContactsRepository
) {
    suspend operator fun invoke(contactId: Long, category: Category) {
        repository.updateCategory(contactId, category)
    }
}
