package app.sila.feature.arham.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * اختبارات حساب تنبيهات المناسبات (§M): صباح اليوم نفسه دائماً، واليوم السابق اختياري
 * بالراية فقط — بلا إطلاق مزدوج ليومٍ واحد، وبلا تنبيه خارج النافذتين.
 */
class ComputeOccasionAlertsUseCaseTest {

    private val compute = ComputeOccasionAlertsUseCase()

    private fun occasion(daysUntil: Int, contactId: Long = 1L) = ArhamOccasion(
        contactId = contactId,
        displayName = "قريب",
        type = "BIRTHDAY",
        label = null,
        month = 3,
        day = 1,
        year = null,
        nextOccurrence = kotlinx.datetime.LocalDate(2026, 3, 1),
        daysUntil = daysUntil,
    )

    @Test
    fun onTheDay_firesOnDayAlert() {
        val alerts = compute(listOf(occasion(0)))
        assertEquals(1, alerts.size)
        assertIs<OccasionAlert>(alerts[0])
        assertEquals(OccasionAlertKind.ON_DAY, alerts[0].kind)
    }

    @Test
    fun dayBefore_gatedByFlag() {
        assertTrue(compute(listOf(occasion(1))).isEmpty()) // الراية ساكتة → لا تنبيه
        val alerts = compute(listOf(occasion(1)), includeDayBefore = true)
        assertEquals(1, alerts.size)
        assertEquals(OccasionAlertKind.DAY_BEFORE, alerts[0].kind)
    }

    @Test
    fun onTheDay_firesEvenWithDayBeforeEnabled_exactlyOnce() {
        val alerts = compute(listOf(occasion(0)), includeDayBefore = true)
        assertEquals(1, alerts.size)
        assertEquals(OccasionAlertKind.ON_DAY, alerts[0].kind)
    }

    @Test
    fun withinWindowButNeitherDay_firesNothing() {
        val occasions = listOf(occasion(2), occasion(3), occasion(7))
        assertTrue(compute(occasions).isEmpty())
        assertTrue(compute(occasions, includeDayBefore = true).isEmpty())
    }

    @Test
    fun mixedOccasions_produceStableOrder_perOccasion() {
        val alerts = compute(listOf(occasion(1, contactId = 9), occasion(0, contactId = 4)), includeDayBefore = true)
        assertEquals(2, alerts.size)
        assertEquals(OccasionAlertKind.DAY_BEFORE, alerts[0].kind)
        assertEquals(9L, alerts[0].occasion.contactId)
        assertEquals(OccasionAlertKind.ON_DAY, alerts[1].kind)
        assertEquals(4L, alerts[1].occasion.contactId)
    }

    @Test
    fun negativeDaysUntil_firesNothing_tolerantly() {
        // حذرة دفاعية: المشتق قد يأتي من مصدر آخر؛ القيم السالبة ليست «اليوم» ولا «غداً».
        assertTrue(compute(listOf(occasion(-3)), includeDayBefore = true).isEmpty())
    }

    @Test
    fun emptyInput_yieldsNoAlerts() {
        assertTrue(compute(emptyList()).isEmpty())
        assertTrue(compute(emptyList(), includeDayBefore = true).isEmpty())
    }
}
