package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة عدد جهات الاتصال في كل تصنيف (SRP).
 */
class ObserveCategoryCountsUseCase(
    private val repository: ContactsRepository
) {
    operator fun invoke(): Flow<Map<Category, Int>> = repository.observeCategoryCounts()
}
