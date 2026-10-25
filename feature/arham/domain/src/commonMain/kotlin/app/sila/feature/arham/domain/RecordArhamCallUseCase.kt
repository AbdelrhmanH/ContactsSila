package app.sila.feature.arham.domain

import app.sila.core.common.AppClock

/**
 * حالة استخدام أحادية المسؤولية: تسجيل مكالمة صلة رحم وتحديث آخر تواصل (SRP).
 */
class RecordArhamCallUseCase(
    private val repository: ArhamRepository,
    private val clock: AppClock
) {
    suspend operator fun invoke(contactId: Long, timestampMillis: Long = clock.nowEpochMillis()) {
        repository.recordContactCall(contactId = contactId, timestampMillis = timestampMillis)
    }
}
