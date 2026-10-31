package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * حالة استخدام أحادية المسؤولية: مراقبة عدد جهات الاتصال في كل تصنيف (SRP).
 *
 * الأعداد تُحترم فلتر الحساب (Issue #24 — "الأعداد حية ومتسقة مع الفلاتر"):
 * بلا فلتر تُقرأ أعداد الـ DAO المجمعة؛ ومع فلتر غير [AccountFilter.ALL] تُجمَّع
 * من قائمة الجهات الحيّة المطابقة — بنفس دلالات الـ DAO (كل قيم [Category]
 * بمفاتيح صفرية، القبور مستبعدة أصلاً من الكاش).
 */
class ObserveCategoryCountsUseCase(
    private val repository: ContactsRepository
) {
    operator fun invoke(accountFilter: AccountFilter = AccountFilter.ALL): Flow<Map<Category, Int>> =
        if (accountFilter == AccountFilter.ALL) {
            repository.observeCategoryCounts()
        } else {
            repository.observeContacts().map { contacts ->
                val filtered = contacts.filter { accountFilter.matches(it) }
                Category.entries.associateWith { category ->
                    filtered.count { it.category == category }
                }
            }
        }
}
