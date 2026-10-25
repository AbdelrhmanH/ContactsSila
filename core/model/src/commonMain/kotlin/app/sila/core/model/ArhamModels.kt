package app.sila.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

private const val MILLIS_PER_DAY = 86_400_000.0

@Serializable
enum class ArhamPeriod {
    WEEK,
    MONTH
}

@Serializable
data class ArhamItem(
    val rank: Int,
    val contactId: Long,
    val displayName: String,
    val photoUri: String? = null,
    val relation: Relation,
    val frequency: ArhamFrequency,
    val daysSinceLastContact: Int?,
    val urgency: Double,
    val status: ArhamStatus,
    val primaryPhone: String
)

@Serializable
data class ArhamDashboardStats(
    val period: ArhamPeriod = ArhamPeriod.WEEK,
    val topThree: List<ArhamItem> = emptyList(),
    val totalFamilyCalls: Int = 0,
    val longestCallSec: Int = 0,
    val contactedRelatives: Int = 0,
    val goalCompletion: Float = 0f
)

@Serializable
data class RecurrenceRule(
    val type: RecurrenceType,
    val daysOfWeek: Set<DayOfWeek>? = null,
    val dayOfMonth: Int? = null,
    val timeOfDay: LocalTime = LocalTime(20, 0)
)

@Serializable
data class ScheduledMessage(
    val id: Long,
    val contactId: Long,
    val contactName: String,
    val type: AutomationType,
    val messageText: String? = null,
    val recurrence: RecurrenceRule,
    val nextFireAt: Long,
    val isEnabled: Boolean = true
)

@Serializable
data class ArhamInboxMessage(
    val id: Long,
    val senderName: String,
    val messageText: String,
    val reaction: String? = null,
    val direction: MessageDirection,
    val status: MessageStatus,
    val sentAt: Long
)

@Serializable
data class EmergencyRingingSession(
    val id: Long,
    val contactId: Long,
    val contactName: String,
    val phoneE164: String,
    val attempts: Int,
    val maxAttempts: Int = 10,
    val status: RingingStatus,
    val startedAt: Long
)

/**
 * حساب الأولوية (Urgency) لقريب في أرحام (DATA_MODEL.md §5).
 */
fun ArhamInfo.calculateUrgency(now: Instant): Double {
    if (frequency == ArhamFrequency.OCCASIONS || frequency.days <= 0) return 0.0
    val paused = pausedUntil
    if (paused != null && paused > now.toEpochMilliseconds()) return 0.0
    val last = lastContactedAt ?: return Double.MAX_VALUE
    val days = (now.toEpochMilliseconds() - last).coerceAtLeast(0L) / MILLIS_PER_DAY
    return days / frequency.days
}

/**
 * تحويل درجة الأولوية إلى حالة لونية (أحمر / أصفر / أخضر).
 */
fun Double.toArhamStatus(): ArhamStatus = when {
    this >= 1.0 -> ArhamStatus.OVERDUE
    this >= 0.7 -> ArhamStatus.SOON
    else -> ArhamStatus.OK
}
