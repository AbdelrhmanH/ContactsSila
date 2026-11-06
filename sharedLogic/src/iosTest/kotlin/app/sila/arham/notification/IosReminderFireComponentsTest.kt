package app.sila.arham.notification

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبارات مركّبات إطلاق التذكير اليومي (T8-12 · خطة سطر 552) — الدالة النقية
 * [fireComponentsOf] فوق منطقة زمنية مثبّتة: الحقول الخمسة تُشتق محلياً في المنطقة
 * المحقونة (التحقق عبر UTC ثم منطقة إقليمية)، والتاريخ الكامل يضمن أول إطلاق صحيح
 * بعد الإزاحة عبر منتصف الليل.
 */
class IosReminderFireComponentsTest {

    @Test
    fun fireComponents_utc_eveningPrecision() {
        val fireAtMillis = Instant.parse("2026-11-06T20:00:00Z").toEpochMilliseconds()

        val components = fireComponentsOf(fireAtMillis = fireAtMillis, timeZone = TimeZone.UTC)

        assertEquals(2026L, components.year)
        assertEquals(11L, components.month)
        assertEquals(6L, components.day)
        assertEquals(20L, components.hour)
        assertEquals(0L, components.minute)
    }

    @Test
    fun fireComponents_regionalZone_derivesLocalFields_notUtc() {
        // 20:00Z = 22:00 بتوقيت القاهرة — الحقول محلية المنطقة لا UTC (المنطقة المحقونة حكم):
        val fireAtMillis = Instant.parse("2026-11-06T20:00:00Z").toEpochMilliseconds()

        val components = fireComponentsOf(
            fireAtMillis = fireAtMillis,
            timeZone = TimeZone.of("Africa/Cairo"),
        )

        assertEquals(2026L, components.year)
        assertEquals(11L, components.month)
        assertEquals(6L, components.day)
        assertEquals(22L, components.hour, "التحول المحلي للمنطقة الإقليمية يُطبَّق حرفياً")
        assertEquals(0L, components.minute)
    }

    @Test
    fun fireComponents_afterMidnightShift_dayMovesWithClock() {
        // 23:30Z بنفس اليوم — الزنّاد اليومي المتكرر يعتمد الساعة/الدقيقة، والتاريخ الكامل
        // يضمن أول إطلاقٍ صحيح لو كانت الإزاحة عبرت منتصف الليل:
        val fireAtMillis = Instant.parse("2026-11-06T23:30:00Z").toEpochMilliseconds()

        val components = fireComponentsOf(fireAtMillis = fireAtMillis, timeZone = TimeZone.UTC)

        assertEquals(6L, components.day)
        assertEquals(23L, components.hour)
        assertEquals(30L, components.minute)
    }
}
