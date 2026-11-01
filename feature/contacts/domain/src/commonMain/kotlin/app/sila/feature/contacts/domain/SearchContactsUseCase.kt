package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * مسار البحث الإنتاجي (Issue #24): يفوّض المستودع الذي يستعلم فهرس FTS4 + أجزاء
 * الهواتف بمعاملات مُقيَّدة ويستبعد القبور — طبقة العرض لا تلمس Room/SQLite إطلاقاً.
 *
 * الاستعلام الفارغ/الأبيض يعيد كل الجهات الحيّة (عقد الـ DAO) — والـ debounce
 * (150ms) مسؤولية طبقة العرض فوق هذا العقد.
 */
class SearchContactsUseCase(private val repository: ContactsRepository) {

    operator fun invoke(query: String): Flow<List<Contact>> = repository.searchContacts(query)
}
