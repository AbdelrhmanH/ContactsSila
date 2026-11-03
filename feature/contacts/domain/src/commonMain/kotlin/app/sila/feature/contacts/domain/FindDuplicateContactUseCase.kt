package app.sila.feature.contacts.domain

import app.sila.core.model.Contact

/**
 * حالة استخدام فحص التكرار أثناء التحرير (T6 · J-9 · Issue #27) — تفويض نقي للمستودع.
 *
 * **النطاق محلي حصراً (J-9):** فحص Room عبر [ContactsRepository.findContactsByE164] —
 * بلا شبكة ولا دليل خدمات في هذه الدورة (توسيع الدليل قرار T8/T9). الاستدعاء غير
 * مانع (ينادى بعد debounce 400ms من العرض) ونتيجته تحذير غير مانع للحفظ.
 *
 * @param excludeContactId جهة التحرير الحالية — لا تُعدّ نفسها تكراراً.
 */
class FindDuplicateContactUseCase(
    private val repository: ContactsRepository,
) {
    suspend operator fun invoke(e164: String, excludeContactId: Long? = null): List<Contact> =
        if (e164.isBlank()) emptyList() else repository.findContactsByE164(e164, excludeContactId)
}
