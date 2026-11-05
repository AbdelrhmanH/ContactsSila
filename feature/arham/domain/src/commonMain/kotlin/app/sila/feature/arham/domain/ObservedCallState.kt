package app.sila.feature.arham.domain

/**
 * نهاية مكالمة مرصودة من انتقالات حالة الهاتف — بدائيات حصراً (بلا رقم ولا جهة —
 * المراقب مراقبُ حالةٍ فقط، AC-17): لحظة رفع السماعة، لحظة العودة إلى الخمول،
 * وهل رُصد رنينٌ (وارد) قبل ذلك في الدورة نفسها — لقاعدة التمييز في الربط.
 */
data class ObservedCallEnd(
    val offhookAtMillis: Long,
    val idleAtMillis: Long,
    /** رنين رُصد منذ آخر نهاية — وجوده بعد بدء المحاولة يعني أن الـ OFFHOOK لمكالمة واردة مجابة. */
    val ringingAtMillis: Long? = null,
)

/**
 * حامل انتقالات حالة الهاتف (نقي بلا منصة — Konsist 4a): يحفظ أول رنين وأول رفع سماعة
 * منذ آخر نهاية مرصودة، ويُخرج [ObservedCallEnd] عند العودة إلى الخمول ثم يُصفَّر.
 *
 * قواعد الدورة:
 * - الرنين/الرفع المتكرر بلا نهاية يُهمل (الأول يكفي — لقاعدة التمييز لا يلزم إلا الوجود).
 * - عودة إلى الخمول بلا رفع سماعة (مكالمة واردة لم تُجب) تُنتج `null` وتُصفّر الحامل —
 *   فلا يبقى رنينٌ قديمٌ يتسرب إلى الدورة التالية.
 * - الصفح بعد الرفع بزمنٍ غير موجب لا يُنتج نهاية (fail-safe — بلا مدد مُختلقة).
 */
class ObservedCallState {

    private var ringingAtMillis: Long? = null
    private var offhookAtMillis: Long? = null

    fun onRinging(atMillis: Long) {
        if (ringingAtMillis == null) ringingAtMillis = atMillis
    }

    fun onOffhook(atMillis: Long) {
        if (offhookAtMillis == null) offhookAtMillis = atMillis
    }

    /**
     * العودة إلى الخمول: تُنتج نهايةً إذا رُفعت السماعة وكان الانتقال موجباً، ثم تُصفّر
     * الحامل دوماً — بداية كل دورة نظيفة.
     */
    fun onIdle(atMillis: Long): ObservedCallEnd? {
        val offhook = offhookAtMillis
        val end = if (offhook != null && atMillis > offhook) {
            ObservedCallEnd(
                offhookAtMillis = offhook,
                idleAtMillis = atMillis,
                ringingAtMillis = ringingAtMillis,
            )
        } else {
            null
        }
        ringingAtMillis = null
        offhookAtMillis = null
        return end
    }
}
