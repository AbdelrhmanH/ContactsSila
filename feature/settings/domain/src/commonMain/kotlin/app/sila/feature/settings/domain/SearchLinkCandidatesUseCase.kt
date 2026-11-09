package app.sila.feature.settings.domain

import app.sila.core.model.Contact

/**
 * حالة استخدام أحادية المسؤولية: البحث عن مرشّحي الربط اليدوي لسجلات غير مطابقة (SRP).
 *
 * القاعدة: أقل من حرفين بعد تقليم الفراغات لا يستهلك بحثاً — القائمة المفتوحة على
 * الدفتر كله غير عملية للربط، فتعيد [invoke] قائمة فارغة دون مناداة المستودع إطلاقاً.
 */
class SearchLinkCandidatesUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(query: String): List<Contact> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()
        return repository.searchLinkCandidates(trimmed)
    }

    private companion object {
        /** أدنى طول استعلام يُستهلك عنده بحث فعلي (حرفان). */
        const val MIN_QUERY_LENGTH = 2
    }
}
