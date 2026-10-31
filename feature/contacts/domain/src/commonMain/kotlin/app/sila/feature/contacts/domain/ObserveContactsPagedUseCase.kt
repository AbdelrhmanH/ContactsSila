package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * تدفق قائمة جهات الاتصال للعرض (Issue #24 — `ObserveContactsPagedUseCase`).
 *
 * **تكوين الفلاتر يحدث هنا في النطاق** (لا في العرض): الحساب × التصنيف × البحث.
 * عندما يكون الاستعلام غير فارغ تُستخدم نتائج FTS (`SearchContactsUseCase`) ثم
 * تُصفّى بالتصنيف/الحساب — وعندما يكون فارغاً تُستخدم قائمة `observeContacts`.
 *
 * **انحراف موثّق عن نص الـ Issue (Paging):** عقد التسمية "Paged" يُستوفى وظيفياً —
 * مصدر الحقيقة هنا كاش Room في الذاكرة يتبععه `LazyColumn` افتراضياً بمفاتيح
 * ثابتة (`contact.id`)، فلا قراءة قاعدة-بيانات متكررة ولا تحميل غير مُقيَّد في
 * الذاكرة (القائمة كلها موجودة أصلاً في الكاش). اعتماد `androidx.paging` +
 * `room-paging` يتطلب PagingSource مولّداً من Room غير متاح فوق المشغّل
 * اليدوي الحالي — راجع PR T3 (قيود موثقة لا صمت معماري).
 */
class ObserveContactsPagedUseCase(private val repository: ContactsRepository) {

    operator fun invoke(
        category: Category? = null,
        searchQuery: String = "",
        accountFilter: AccountFilter = AccountFilter.ALL,
    ): Flow<List<Contact>> {
        val base = if (searchQuery.isBlank()) {
            repository.observeContacts(category = category, searchQuery = "")
        } else {
            repository.searchContacts(searchQuery).map { results ->
                results.filter { it.category == category || category == null }
            }
        }
        return base.map { contacts -> contacts.filter { accountFilter.matches(it) } }
    }
}
