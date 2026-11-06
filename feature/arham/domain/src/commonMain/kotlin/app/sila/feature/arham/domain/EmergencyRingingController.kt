package app.sila.feature.arham.domain

/**
 * منفذ حلقة الرنين — الحد المُحقون بين النطاق المشترك والمنصة (T8-10 · §J: «حلقة المنصة
 * حدٌّ محقون» · Issue #29). النطاق يقرر البدء/التوقف/الحسم؛ المنصة تنفّذ فقط.
 *
 * العقود idempotent: `start` لجلسة تجري أصلاً بلا أثر، و`stop` قبل البدء/بعد الحسم
 * عمليات ساكنة. بلا هوية في أي توقيع إلا `contactId` المعرفي (بلا أرقام/أسماء).
 */
interface EmergencyRingingController {

    /** يبدأ حلقة المنصة للجلسة (FGS `phoneCall`) — ids-only؛ الفشل يُسجَّل بنوعٍ ولا يُرمى. */
    fun start(sessionId: Long, contactId: Long)

    /** يوقف الحلقة والخدمة الأمامية — idempotent. */
    fun stop()

    /** هل الحلقة تجري فعلاً لهذه الجلسة؟ (تسوية §N.5: إعادة الالتحاق مقابل العالق). */
    fun isRunning(sessionId: Long): Boolean
}
