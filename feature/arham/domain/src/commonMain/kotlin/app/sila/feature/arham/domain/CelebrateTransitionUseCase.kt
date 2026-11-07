package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamStatus

/**
 * حالة استخدام: كاشف الانتقال أحمر←أخضر المُحكم بسياسة الاحتفال (خطة §D.3-5).
 *
 * يُستدعى من العارض على **فرق انبعاثاتٍ في الذاكرة** لقائمة الأولويات (الجدولة الحقيقية
 * خارج النطاق — إن غاب المستخدم عن التطبيق عبر إعادة تشغيل فلا احتفال بأثر رجعي؛ قيد
 * موثَّق في سجل الملاحظات). القواعد:
 * - الاحتفال عند الانتقال من [ArhamStatus.OVERDUE] (أحمر) إلى [ArhamStatus.OK] (أخضر) حصراً —
 *   أحمر←أصفر وأصفر←أخضر لا يُحتفلان.
 * - باب الاحتفال مغلقة إن كانت الكونفيتي معطلة في التفضيلات.
 * - الاحتفال مرة واحدة في فترة الأسبوع لكل جهة ([PERIOD_MILLIS]): مفتاح الفترة
 *   `nowEpochMillis() / PERIOD_MILLIS` — إعادة التركيب أو green→red→green داخل الفترة
 *   نفسها لا يعيد الإطلاق (العلامة تُقرأ قبل الإطلاق).
 * - **العلامة تُكتب بعد القرار حصراً**: القرار الرافض (باب مغلقة/فترة مسجَّلة) لا يُلوِّث
 *   الفترة — إن عطّل المستخدم الكونفيتي وهب قبل انتهاء الفترة فسيتاح له الاحتفال لاحقاً.
 *
 * يُرجع `true` للأثر الوحيد: إطلاق [إظهار الاحتفال] (ShowCelebrationConfetti).
 */
class CelebrateTransitionUseCase(
    private val policy: ArhamCelebrationPolicy,
    private val clock: AppClock,
) {
    suspend operator fun invoke(
        contactId: Long,
        previousStatus: ArhamStatus,
        currentStatus: ArhamStatus,
    ): Boolean {
        if (previousStatus != ArhamStatus.OVERDUE || currentStatus != ArhamStatus.OK) return false
        if (!policy.isConfettiEnabled()) return false
        val periodKey = clock.nowEpochMillis() / PERIOD_MILLIS
        if (policy.celebratedPeriodKey(contactId) == periodKey) return false
        policy.markCelebrated(contactId = contactId, periodKey = periodKey)
        return true
    }

    companion object {
        /**
         * طول فترة الاحتفال: 7 أيام — الاحتفال بجهةٍ مرة واحدة في الأسبوع كحدّ أقصى،
         * ومفاتيح الفترات تتصاعد زمنياً فلا تصادم بين الفترات.
         */
        const val PERIOD_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
