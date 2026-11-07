package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.RingingStatus

/**
 * حالة استخدام: تسوية الجلسات العالقة بعد موت العملية (T8-10 · §N.5/§O.7 · Issue #29).
 *
 * **لا جائز أن ينجو الرنين من موت العملية — المحاسبة فقط تنجو**: صف `IN_PROGRESS`
 * تركته عملية ماتت يُغلق `INTERRUPTED` عند بدء العملية التالية، والمحاولات المحسوبة
 * تبقى في عدّاد حد المعدل (D4 يعدّ كل الحالات). حارسا الأمان قبل الإغلاق:
 * 1. الحلقة تجري فعلاً (إعادة التحاق sticky لخدمةٍ ما زالت حية) ⇒ لا تُلمس.
 * 2. أحدث محاولة أحدث من حد الحياة (30 د — مقترح مُفصَح في [EmergencySessionPolicy])
 *    ⇒ قد تُستأنف قانونياً ⇒ لا تُلمس.
 */
class ReconcileInterruptedSessionUseCase(
    private val repository: EmergencySessionRepository,
    private val controller: EmergencyRingingController,
    private val clock: AppClock,
    private val policy: EmergencySessionPolicy = EmergencySessionPolicy(),
) {

    /** @return معرف الجلسة المُغلقة إن وُجدت — `null` = لا جلسة عالقة (أو محمية بالحواجز). */
    suspend operator fun invoke(): Long? {
        val orphan = repository.findInProgressSession() ?: return null
        if (controller.isRunning(orphan.id)) return null // إعادة التحاق — الجلسة حية
        val now = clock.nowEpochMillis()
        if (!policy.isStaleInProgress(lastAttemptAtMillis = orphan.lastAttemptAt, nowMillis = now)) return null
        val closed = repository.finishSession(orphan.id, RingingStatus.INTERRUPTED, now)
        return if (closed) orphan.id else null
    }
}
