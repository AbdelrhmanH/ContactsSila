package app.sila.core.common.crash

/**
 * خوارزمية الاحتفاظ المشتركة (ADR-0019): أحدث [max] تقريراً يبقى، وما فوقه
 * قديم يُحذف — الترتيب المعجمي على `crash-<millis>` ثابت الزمن (طابع epoch
 * بطول ثابت عملياً ⇒ الترتيب المعجمي = الترتيب الزمني).
 *
 * دالة نقية مشتركة بين sink المنصتين (Android/iOS) ليمكن إثبات سقف الاحتفاظ
 * باختبار مضيف مشترك واحد — سابقاً كانت المنطق مكرراً خاصاً بكل منصة.
 */
public fun staleReportNames(names: List<String>, max: Int): List<String> =
    names.sortedDescending().drop(max)
