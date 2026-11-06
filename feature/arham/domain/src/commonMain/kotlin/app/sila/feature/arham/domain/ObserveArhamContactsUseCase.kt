package app.sila.feature.arham.domain

import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * مراقبة أقارب أرحام الخام (T8-11 · Issue #29) — واجهة رقيقة فوق [ArhamRepository]
 * لسطح العرض: أوراق اختيار الوالد في الورقة القرابية، وحلّ رقم زر الاتصال في
 * ورقة عقدة الشجرة (العقدة تحمل الهوية حصراً — بلا PII في نموذج الرسم).
 */
class ObserveArhamContactsUseCase(
    private val repository: ArhamRepository,
) {
    operator fun invoke(): Flow<List<Contact>> = repository.observeArhamContacts()
}
