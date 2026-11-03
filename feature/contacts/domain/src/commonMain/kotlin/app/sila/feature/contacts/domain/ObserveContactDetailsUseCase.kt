package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.model.Contact
import app.sila.core.model.RecentInteraction
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام مرصد تفاصيل جهة الاتصال (T6 · Issue #27) — SRP: تفويض نقي للمستودع.
 *
 * الحالة الكاملة (كل الأطفال صِلَة-فقط ضمنها — ADR-0012) تُبثّ تفاعلياً من كاش
 * الذاكرة الذي تتحدثه كل معاملة كتابة ناجحة (SSOT — ARCHITECTURE.md §5).
 */
class ObserveContactDetailsUseCase(
    private val repository: ContactsRepository,
) {
    operator fun invoke(contactId: Long): Flow<Contact?> = repository.observeContactById(contactId)

    suspend fun getOnce(contactId: Long): Contact? = repository.getContactById(contactId)

    /** «التفاعلات الأخيرة» — قراءة Room حصراً (بلا `READ_CALL_LOG` في مسار التفاصيل). */
    suspend fun recentInteractions(contact: Contact, limit: Int = DEFAULT_LIMIT): List<RecentInteraction> {
        val e164s = contact.phones.map { it.e164 }.filter { it.isNotBlank() }
        if (e164s.isEmpty()) return emptyList()
        return repository.getRecentCallEvents(e164s, limit)
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 5
    }
}
