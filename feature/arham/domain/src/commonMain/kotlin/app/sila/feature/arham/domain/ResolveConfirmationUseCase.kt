package app.sila.feature.arham.domain

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallEvent

/**
 * حالة استخدام: حسم بطاقة تأكيد معلّقة بإجابة المستخدم الأربعية (ADR-0006 §5).
 *
 * يُقبل حسمُ الحدث وهو `PENDING` فقط — بطاقة التأكيد تُرفع بعد الحسم الأول ولا يُعاد
 * فتحها أو قلب قرار المستخدم (`REJECTED` نهائي). إجابة «تم» المؤهلة تُحدّث `lastContactedAt`
 * وفق قاعدة AC-8 عبر المستودع (`max(lastContactedAt, timestamp)`).
 */
class ResolveConfirmationUseCase(
    private val repository: ArhamCallEventRepository,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) {

    /**
     * @return الحدث بعد الحسم، أو خطأ `NotFound` لمعرف غير موجود (بطاقة بائتة)
     * أو `Conflict` لحدث ليس `PENDING`.
     */
    suspend operator fun invoke(eventId: String, answer: ConfirmationAnswer): AppResult<CallEvent> {
        val event = repository.getEvent(eventId)
            ?: return AppResult.Error(DataError.NotFound(entityId = eventId))
        if (event.confirmation != CallConfirmation.PENDING) {
            return AppResult.Error(
                DataError.Conflict(reason = "الحدث ليس في انتظار التأكيد: ${event.confirmation.name}"),
            )
        }
        val resolved = stateMachine.resolveConfirmation(event, answer)
        if (resolved == event) return AppResult.Success(resolved) // «لاحقاً» — بلا تغيير ولا كتابة
        repository.upsertEvent(resolved)
        val contactId = resolved.contactId
        if (contactId != null && stateMachine.updatesLastContacted(resolved)) {
            repository.updateLastContacted(contactId = contactId, timestampMillis = resolved.timestamp)
        }
        return AppResult.Success(resolved)
    }
}
