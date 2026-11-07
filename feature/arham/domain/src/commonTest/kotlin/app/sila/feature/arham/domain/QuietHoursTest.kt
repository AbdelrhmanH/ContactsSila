package app.sila.feature.arham.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * اختبارات دلالات ساعات الهدوء (T8-08 · §M): النافذة `[start, end)` بالاتجاهين،
 * الشمولية عند البداية والاستثناء عند نهاية الاستيقاظ، النافذة الصفرية، والساعات
 * غير الصالحة — قرارات مُثبَّتة حتّياً فوق UTC لضمان قيم حتمية (بلا إزاحة صيفية).
 */
class QuietHoursTest {

    private val utc = TimeZone.UTC

    /** لحظة مرشّحة بالميلي ثانية من ساعة محلية UTC. */
    private fun atLocal(hour: Int, minute: Int = 0, day: Int = 27): Long =
        LocalDateTime(2026, 2, day, hour, minute).toInstant(utc).toEpochMilliseconds()

    private fun defer(fireAtMillis: Long, startHour: Int, endHour: Int): Long =
        QuietHours.deferIfQuiet(
            fireAtMillis = fireAtMillis,
            timeZone = utc,
            startHour = startHour,
            endHour = endHour,
        )

    // ===== النافذة الليلية الافتراضية 23→8 (عابرة منتصف الليل) =====

    @Test
    fun `evening side of overnight window defers to next-day end`() {
        assertEquals(atLocal(hour = 8, day = 28), defer(atLocal(hour = 23, minute = 30), 23, 8))
    }

    @Test
    fun `start hour is inclusive in overnight window`() {
        assertEquals(atLocal(hour = 8, day = 28), defer(atLocal(hour = 23), 23, 8))
    }

    @Test
    fun `minute before start stays unchanged in overnight window`() {
        assertEquals(atLocal(hour = 22, minute = 59), defer(atLocal(hour = 22, minute = 59), 23, 8))
    }

    @Test
    fun `morning side of overnight window defers to same-day end`() {
        assertEquals(atLocal(hour = 8), defer(atLocal(hour = 2), 23, 8))
    }

    @Test
    fun `minute before wake defers to same-day end`() {
        assertEquals(atLocal(hour = 8), defer(atLocal(hour = 7, minute = 59), 23, 8))
    }

    @Test
    fun `wake hour is exclusive and stays unchanged`() {
        assertEquals(atLocal(hour = 8), defer(atLocal(hour = 8), 23, 8))
    }

    @Test
    fun `midday stays unchanged in overnight window`() {
        assertEquals(atLocal(hour = 12), defer(atLocal(hour = 12), 23, 8))
    }

    // ===== نافذة نهارية 13→15 (الاتجاه الآخر) =====

    @Test
    fun `daytime window defers to same-day end`() {
        assertEquals(atLocal(hour = 15), defer(atLocal(hour = 13, minute = 30), 13, 15))
    }

    @Test
    fun `daytime window start inclusive and minute before end defers`() {
        assertEquals(atLocal(hour = 15), defer(atLocal(hour = 13), 13, 15))
        assertEquals(atLocal(hour = 15), defer(atLocal(hour = 14, minute = 59), 13, 15))
    }

    @Test
    fun `daytime window end exclusive and before-start unchanged`() {
        assertEquals(atLocal(hour = 15), defer(atLocal(hour = 15), 13, 15))
        assertEquals(atLocal(hour = 12, minute = 59), defer(atLocal(hour = 12, minute = 59), 13, 15))
    }

    // ===== النافذة الصفرية والساعات غير الصالحة (معطَّلة — لا تُختلق نافذة) =====

    @Test
    fun `zero-length window start equals end means no quiet hours`() {
        assertEquals(atLocal(hour = 8), defer(atLocal(hour = 8), 8, 8))
        assertEquals(atLocal(hour = 23), defer(atLocal(hour = 23), 8, 8))
    }

    @Test
    fun `hours outside 0-23 disable quiet hours entirely`() {
        val fireAt = atLocal(hour = 13)
        assertEquals(fireAt, defer(fireAt, 24, 8))
        assertEquals(fireAt, defer(fireAt, 23, -1))
    }
}
