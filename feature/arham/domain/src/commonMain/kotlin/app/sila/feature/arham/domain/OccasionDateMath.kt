package app.sila.feature.arham.domain

import kotlinx.datetime.LocalDate

/**
 * رياضيات التواريخ السنوية للمناسبات المشتقة (T8-05) — نقية بلا ساعة ولا منطقة زمنية
 * ضمنية: تُمرَّر `LocalDate` صريحة من حالة الاستخدام المحقِنة (قاعدة §M المشتركة).
 */

/** هل السنة كبيسة (غريغوري: قاعدة 4/100/400). */
internal fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

/** عدد أيام الشهر في سنة معلومة الكبيسة؛ 0 لشهر غير صالح. */
internal fun daysInMonth(month: Int, isLeap: Boolean): Int = when (month) {
    2 -> if (isLeap) 29 else 28
    4, 6, 9, 11 -> 30
    1, 3, 5, 7, 8, 10, 12 -> 31
    else -> 0
}

/**
 * أقرب تكرار سنوي لـ (month, day) بدءاً من [today] **شاملاً اليوم نفسه**: إن مرّ موعد
 * هذا العام صار التكرار في العام التالي.
 *
 * قاعدة 29 فبراير (موثقة ويُثبّتها الاختبار): في السنوات غير الكبيسة تُقْلَم إلى
 * 28 فبراير — فيصبح يومُ المناسبة وتنبيهُها 28 فبراير ذاك العام. أما التواريخ غير
 * الموجودة أصلاً (30 فبراير، 32 من أي شهر…) فتُهمل بإرجاع null بلا انهيار — بيانات
 * دفتر العناوين من الجهاز غير موثوقة، وإهلالها يُخفق مغلقاً وفق عُرف المشروع.
 */
internal fun resolveNextAnnualOccurrence(month: Int, day: Int, today: LocalDate): LocalDate? {
    if (month !in 1..12 || day !in 1..31) return null
    val year =
        if (month > today.monthNumber ||
            (month == today.monthNumber && day >= today.dayOfMonth)
        ) {
            today.year
        } else {
            today.year + 1
        }
    val leap = isLeapYear(year)
    val effectiveDay = if (month == 2 && day == 29 && !leap) 28 else day
    if (effectiveDay > daysInMonth(month, leap)) return null
    return LocalDate(year, month, effectiveDay)
}

/** أيام كاملة من [today] إلى [target] (موجب إن كانت قادمة، 0 إن هي اليوم). */
internal fun daysUntil(today: LocalDate, target: LocalDate): Int =
    (target.toEpochDays() - today.toEpochDays()).toInt()
