package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.Category

/**
 * حالة استخدام: بدء رنين طوارئ (T8-10 · AC-14 · §N.2 · Issue #29).
 *
 * الترتيب المُصادق: بدايةً `IN_PROGRESS` حصراً (idempotent)، ثم بوابة الفئة
 * [Category.EMERGENCY]، ثم حكم stop-on-block المبكر (§N.4 — جهة محجوبة لا تُرنّ ولا
 * مرة)، ثم حد المعدل (D4: العدّاد الصارم < 3/24س)، ثم إنشاء الصف، ثم إطلاق حلقة
 * المنصة عبر المنفذ المحقون. الواجهة (T8-11) تملك طلب إذن `CALL_PHONE`؛ البداية
 * بلا الإذن ترفض عند المنفذ فشلاً هادئاً (بلا انهيار).
 */
class StartEmergencyRingingUseCase(
    private val repository: EmergencySessionRepository,
    private val controller: EmergencyRingingController,
    private val clock: AppClock,
    private val policy: EmergencySessionPolicy = EmergencySessionPolicy(),
) {

    sealed interface StartResult {
        /** بدأت — جلسة `IN_PROGRESS` بالمعرف المولّد وحلقة المنصة مُطلقة. */
        data class Started(val sessionId: Long) : StartResult

        /** جلسة تجري أصلاً — بداية ثانية عملية ساكنة (§N.8). */
        data object AlreadyRunning : StartResult

        /** ليست جهة [Category.EMERGENCY] — البوابة الفئوية حصراً (AC-14). */
        data object NotEmergencyContact : StartResult

        /** جهة محجوبة — stop-on-block المبكر (§N.4). */
        data object ContactBlocked : StartResult

        /** تجاوز حد المعدل: 3 جلسات / 24 ساعة دحرجة (D4 — مقارنة صارمة). */
        data object RateLimited : StartResult

        /** جهة غير موجودة (حُذفت قبل البدء). */
        data object ContactNotFound : StartResult
    }

    suspend operator fun invoke(contactId: Long): StartResult {
        if (repository.findInProgressSession() != null) return StartResult.AlreadyRunning

        val contact = repository.getContactById(contactId) ?: return StartResult.ContactNotFound
        if (contact.category != Category.EMERGENCY) return StartResult.NotEmergencyContact
        if (contact.isBlocked) return StartResult.ContactBlocked

        val now = clock.nowEpochMillis()
        val windowStart = now - policy.rateLimitWindowMillis
        val startedInWindow = repository.countSessionsStartedSince(cutoffMillis = windowStart)
        if (!policy.canStartSession(startedInWindow)) return StartResult.RateLimited

        val sessionId = repository.insertInProgressSession(contactId = contactId, startedAtMillis = now)
        controller.start(sessionId = sessionId, contactId = contactId)
        return StartResult.Started(sessionId)
    }
}
