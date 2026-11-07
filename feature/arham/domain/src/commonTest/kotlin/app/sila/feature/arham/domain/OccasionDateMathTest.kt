package app.sila.feature.arham.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * اختبارات رياضيات التواريخ السنوية للمناسبات (خطة §P-T8-05: سنة بلا سنة مصدر، كبيسة،
 * أطراف النافذة). القاعدة المُثبَّتة: 29 فبراير في سنة غير كبيسة يُقْلَم إلى 28 فبراير،
 * والتواريخ غير الموجودة تُهمل بلا انهيار.
 */
class OccasionDateMathTest {

    @Test
    fun leapYearRules_gregorian() {
        assertTrue(isLeapYear(2024))
        assertTrue(isLeapYear(2000)) // قاعدة 400
        assertFalse(isLeapYear(1900)) // قاعدة 100
        assertFalse(isLeapYear(2026))
    }

    @Test
    fun sameDayNextYear_returnsToday_zeroDaysUntil() {
        val today = LocalDate(2026, 2, 27)
        val next = requireNotNull(resolveNextAnnualOccurrence(2, 27, today))
        assertEquals(LocalDate(2026, 2, 27), next)
        assertEquals(0, daysUntil(today, next))
    }

    @Test
    fun laterThisYear_staysThisYear() {
        val today = LocalDate(2026, 2, 27)
        assertEquals(LocalDate(2026, 3, 5), resolveNextAnnualOccurrence(3, 5, today))
        assertEquals(LocalDate(2026, 12, 31), resolveNextAnnualOccurrence(12, 31, today))
    }

    @Test
    fun passedThisYear_rollsToNextYear() {
        val today = LocalDate(2026, 2, 27)
        assertEquals(LocalDate(2027, 2, 20), resolveNextAnnualOccurrence(2, 20, today))
        assertEquals(LocalDate(2027, 1, 1), resolveNextAnnualOccurrence(1, 1, today))
    }

    @Test
    fun daysUntil_spansMonthsCorrectly() {
        assertEquals(6, daysUntil(LocalDate(2026, 2, 27), LocalDate(2026, 3, 5)))
        assertEquals(7, daysUntil(LocalDate(2026, 2, 27), LocalDate(2026, 3, 6)))
        assertEquals(8, daysUntil(LocalDate(2026, 2, 27), LocalDate(2026, 3, 7)))
    }

    @Test
    fun feb29_nonLeapYear_isClampedToFeb28() {
        val today = LocalDate(2026, 1, 15) // 2026 غير كبيسة
        assertEquals(LocalDate(2026, 2, 28), resolveNextAnnualOccurrence(2, 29, today))
    }

    @Test
    fun feb29_leapYear_keepsFeb29() {
        val today = LocalDate(2028, 1, 15) // 2028 كبيسة
        assertEquals(LocalDate(2028, 2, 29), resolveNextAnnualOccurrence(2, 29, today))
    }

    @Test
    fun feb29_onFeb28OfNonLeapYear_resolvesToday() {
        // القاعدة: المناسبة تُقْلَم إلى 28 ف2 في غير الكبيسة، فيكون 28 ف2 هو يوم التنبيه.
        val today = LocalDate(2026, 2, 28)
        val next = requireNotNull(resolveNextAnnualOccurrence(2, 29, today))
        assertEquals(LocalDate(2026, 2, 28), next)
        assertEquals(0, daysUntil(today, next))
    }

    @Test
    fun invalidDates_areSkippedWithoutCrashing() {
        val today = LocalDate(2026, 2, 27)
        assertNull(resolveNextAnnualOccurrence(13, 1, today)) // شهر غير صالح
        assertNull(resolveNextAnnualOccurrence(0, 10, today))
        assertNull(resolveNextAnnualOccurrence(2, 30, today)) // غير موجود حتى في الكبيسة
        assertNull(resolveNextAnnualOccurrence(4, 31, today)) // نيسان = 30 يوماً
        assertNull(resolveNextAnnualOccurrence(3, 32, today))
        assertNull(resolveNextAnnualOccurrence(3, 0, today))
    }
}
