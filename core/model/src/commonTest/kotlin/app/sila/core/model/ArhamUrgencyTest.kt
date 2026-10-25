package app.sila.core.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ArhamUrgencyTest {

    private val dayMillis = 86_400_000L
    private val now = Instant.fromEpochMilliseconds(30 * dayMillis)

    @Test
    fun calculateUrgency_returnsOverdueWhenDaysExceedFrequency() {
        val info = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = ArhamFrequency.WEEKLY,
            lastContactedAt = now.toEpochMilliseconds() - (10 * dayMillis)
        )
        val urgency = info.calculateUrgency(now)
        assertEquals(ArhamStatus.OVERDUE, urgency.toArhamStatus())
    }

    @Test
    fun calculateUrgency_returnsSoonWhenBetween70And100Percent() {
        val info = ArhamInfo(
            relation = Relation.AUNT_MATERNAL,
            frequency = ArhamFrequency.MONTHLY,
            lastContactedAt = now.toEpochMilliseconds() - (24 * dayMillis) // 24/30 = 0.8
        )
        val urgency = info.calculateUrgency(now)
        assertEquals(ArhamStatus.SOON, urgency.toArhamStatus())
    }

    @Test
    fun calculateUrgency_returnsOkWhenRecentlyContacted() {
        val info = ArhamInfo(
            relation = Relation.MOTHER,
            frequency = ArhamFrequency.WEEKLY,
            lastContactedAt = now.toEpochMilliseconds() - (2 * dayMillis) // 2/7 = 0.28
        )
        val urgency = info.calculateUrgency(now)
        assertEquals(ArhamStatus.OK, urgency.toArhamStatus())
    }
}
