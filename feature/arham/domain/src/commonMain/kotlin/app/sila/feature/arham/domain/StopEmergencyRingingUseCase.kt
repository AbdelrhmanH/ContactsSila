package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.RingingStatus

/**
 * حالة استخدام: إيقاف رنين الطوارئ (T8-10 · §N.8 · Issue #29) — **نفس الانتقال النهائي
 * idempotent** مهما كان مصدر الإيقاف (زر الإشعار/الواجهة/بثّ المتلقي): `STOPPED_BY_USER`
 * بشرط أنها جارية، ثم وقف المنفذ. الإيقاف المزدوج `false` عملية ساكنة — لا حالة ثانية.
 */
class StopEmergencyRingingUseCase(
    private val repository: EmergencySessionRepository,
    private val controller: EmergencyRingingController,
    private val clock: AppClock,
) {

    /**
     * @param sessionId معرف الجلسة المعروف للمستدعي (المتلقي/الواجهة)؛ `null` ⇒ الجلسة
     *   الجارية الوحيدة إن وجدت.
     * @return `true` حسمٌ فعلي، `false` لا جلسة جارية أو حُسمت سلفاً (ساكن).
     */
    suspend operator fun invoke(sessionId: Long? = null): Boolean {
        val id = sessionId
            ?: repository.findInProgressSession()?.id
            ?: return false
        val finished = repository.finishSession(id, RingingStatus.STOPPED_BY_USER, clock.nowEpochMillis())
        controller.stop()
        return finished
    }
}
