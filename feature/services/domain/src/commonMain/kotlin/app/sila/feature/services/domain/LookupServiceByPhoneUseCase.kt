package app.sila.feature.services.domain

import app.sila.core.model.Service

/**
 * حالة استخدام أحادية المسؤولية: فحص الرقم أثناء الإضافة لمعرفة إن كان مسجلاً في الدليل كخدمة (PRD §6.3).
 */
class LookupServiceByPhoneUseCase(
    private val repository: ServicesRepository
) {
    suspend operator fun invoke(rawOrE164Phone: String): Service? =
        repository.lookupByPhoneE164(rawOrE164Phone)
}
