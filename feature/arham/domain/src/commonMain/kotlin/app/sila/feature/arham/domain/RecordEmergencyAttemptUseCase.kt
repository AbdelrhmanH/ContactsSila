package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.RingingStatus

/**
 * حالة استخدام: تسجيل محاولة رنين (T8-10 · §N.4 · Issue #29) — تُستدعى من حلقة المنصة
 * بعد كل جولة (CALL→IDLE→5ث). قبل كل تسجيل تُعاد قراءة الهوية لحظياً (§N.4 حرفياً):
 * محجوبة ⇒ `BLOCKED` (stop-on-block)، محذوفة ⇒ إجحاف الجلسة `INTERRUPTED` (عقد
 * الكيان: «لو حُذفت الجهة وسط جلسة تُجهَض الجلسة»)، ثم تسجيل ذرّي للعدّاد، ثم حسم
 * `MAX_REACHED` عند السقف (10). كل الحسوم عبر [EmergencySessionRepository.finishSession]
 * المقيَّد بـ IN_PROGRESS — idempotent حصراً (§N.8).
 */
class RecordEmergencyAttemptUseCase(
    private val repository: EmergencySessionRepository,
    private val controller: EmergencyRingingController,
    private val clock: AppClock,
    private val policy: EmergencySessionPolicy = EmergencySessionPolicy(),
) {

    sealed interface AttemptResult {
        /** سُجّلت — استمر بالجولة التالية (العدد الجديد). */
        data class ContinueRinging(val attempts: Int) : AttemptResult

        /** بلغ السقف (10) — الجلسة حُسمت `MAX_REACHED` والحلقة توقفت (§N.4). */
        data object MaxReached : AttemptResult

        /** الجهة صارت محجوبة وسط الجلسة — حُسمت `BLOCKED` (stop-on-block). */
        data object Blocked : AttemptResult

        /** الجهة حُذفت وسط الجلسة — أُجهضت `INTERRUPTED`. */
        data object ContactDeleted : AttemptResult

        /** الجلسة محسومة سلفاً — عملية ساكنة (§N.8). */
        data object SessionFinished : AttemptResult
    }

    suspend operator fun invoke(sessionId: Long, contactId: Long): AttemptResult {
        val contact = repository.getContactById(contactId)
        if (contact == null) {
            repository.finishSession(sessionId, RingingStatus.INTERRUPTED, clock.nowEpochMillis())
            controller.stop()
            return AttemptResult.ContactDeleted
        }
        if (contact.isBlocked) {
            repository.finishSession(sessionId, RingingStatus.BLOCKED, clock.nowEpochMillis())
            controller.stop()
            return AttemptResult.Blocked
        }

        val attempts = repository.recordAttempt(sessionId, clock.nowEpochMillis())
            ?: return AttemptResult.SessionFinished

        if (policy.isAttemptCeilingReached(attempts)) {
            repository.finishSession(sessionId, RingingStatus.MAX_REACHED, clock.nowEpochMillis())
            controller.stop()
            return AttemptResult.MaxReached
        }
        return AttemptResult.ContinueRinging(attempts)
    }
}
