package app.sila.feature.arham.presentation

import app.sila.core.model.Relation

/**
 * صف مناسبة جاهز للعرض (T8-11 · O-5/O-8 · Issue #29) — **حقول خام حصراً**: الـ VM
 * لا ينتج نصاً مترجماً (لا مورد ترجمة في العرض النقي)، فالشاشة تبني سطرَي الشخص
 * واليوم عبر [ArhamStrings.occasionPerson]/[ArhamStrings.occasionToday]/
 * [ArhamStrings.occasionInDays]. سياق القرابة يُضمّ من كاش الأقارب (لـ [ArhamOccasion]
 * لا حقل قرابة — O-8: «عمتك سعاد» لا «سعاد» وحدها).
 */
data class OccasionUi(
    val contactId: Long,
    val displayName: String,
    /** قرابة صاحب المناسبة من كاش أقارب أرحام — `null` إن لم تُحلّ (يُعرض الاسم وحده). */
    val relation: Relation?,
    /** أيام حتى المناسبة: 0 = اليوم (ضمن نافذة 7 أيام — اشتقاق T8-08). */
    val daysUntil: Int,
) {
    /** مناسبة اليوم تُعرض بصيغة «اليوم» حصراً. */
    val isToday: Boolean get() = daysUntil == 0
}
