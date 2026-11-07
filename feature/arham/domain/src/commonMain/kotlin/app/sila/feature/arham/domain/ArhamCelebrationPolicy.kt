package app.sila.feature.arham.domain

/**
 * بوابة سياسة الاحتفال (DIP — خطة §D.3-5): صلاحية الاحتفال + علامة «احتُفل بجهة
 * في فترةٍ ما» المُستمَدة على القرص كي لا يعيد التركيب/إعادة التركيب إطلاق
 * الاحتفال نفسه (اختبار D.3-5: لا إطلاق على green→red→green داخل الفترة نفسها).
 *
 * التنفيذ يعيش في جذر التركيب (جسر فوق مصدر التفضيلات — سبق ArhamOccasionBridge)،
 * والنطاق يعرف العقد حصراً — بلا أنواع DataStore أو منصة.
 */
interface ArhamCelebrationPolicy {

    /** هل الاحتفال مفعَّل في التفضيلات (`UserPreferences.confettiEnabled`)؟ */
    suspend fun isConfettiEnabled(): Boolean

    /** مفتاح فترة آخر احتفال مسجَّل لجهة الاتصال، أو `null` إن لم يُحتفل بها قط. */
    suspend fun celebratedPeriodKey(contactId: Long): Long?

    /** يُسجِّل الاحتفال لجهة الاتصال بمفتاح الفترة المعني (بعد القرار لا قبله). */
    suspend fun markCelebrated(contactId: Long, periodKey: Long)
}
