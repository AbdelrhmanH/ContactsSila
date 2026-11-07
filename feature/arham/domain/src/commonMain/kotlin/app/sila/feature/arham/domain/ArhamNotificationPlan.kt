package app.sila.feature.arham.domain

/**
 * نموذج قرار إشعار أرحام اليومي (§M — T8-08): موعد الإطلاق مع محتواه المحتمل.
 *
 * **المحتوى يُحسب وقتَ الجدولة كما هو الآن، والمستقبل (المستقبِل) يعيد حسابه وقتَ
 * الإطلاق فعلياً** (D2 §8: الجدولة مشتقة من Room والتفضيلات عند كل إطلاق) — فالحقل
 * هنا قرارٌ لحظة الاستدعاء، ومرآةٌ للاختبارات، لا وعداً بنصٍّ مجمد.
 */
data class ArhamNotificationPlan(
    /** لحظة الإطلاق القادمة (epoch millis) — بعد إزاحة الوقت المفضّل وساعات الهدوء. */
    val fireAtMillis: Long,
    /**
     * محتوى الإشعار إن وُجد: مناسبةٌ اليوم (أو الغد إذا مُكِّن التنبيه السابق ليومها)
     * أولويةً لأنها حساسة للوقت، وإلا الرسالة المجمعة لمن تُقدَّم أيامهما؟ لا شيء
     * (`null`) إن لم يكن هناك متأخر ولا مناسبة — لا تُختلق حالة إلحاح (درس D.3).
     */
    val content: ArhamNotificationContent?,
)

/** محتوى الإشعار كرموز دلالية — النصوص المترجمة في موارد الإشعارات (لا نصوص في الكود). */
sealed interface ArhamNotificationContent {

    /**
     * الرسالة المجمعة (§M «العم أحمد يشتاق إليك (+2 آخرين)»): أكثر المتأخرين إلحاحاً
     * (المرتبة 01 في قائمة الأولويات) مع عدد المتأخرين الآخرين.
     */
    data class Digest(
        val headlineDisplayName: String,
        val additionalOverdueCount: Int,
    ) : ArhamNotificationContent

    /** تنبيه مناسبة (D1 مشتق): عيد ميلاد أو ذكرى، اليوم أو غداً. */
    data class Occasion(
        val kind: OccasionAlertKind,
        /** نوع التاريخ كما في `ArhamOccasion.type` — BIRTHDAY أو ANNIVERSARY حصراً. */
        val type: String,
        val displayName: String,
    ) : ArhamNotificationContent
}
