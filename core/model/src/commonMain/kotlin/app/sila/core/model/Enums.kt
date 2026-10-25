package app.sila.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Category {
    FAMILY,
    FRIENDS,
    WORK,
    SERVICES,
    EMERGENCY,
    UNCATEGORIZED
}

@Serializable
enum class ServiceVisibility {
    PERSONAL,
    COMMUNITY,
    VERIFIED
}

@Serializable
enum class ArhamFrequency(val days: Int) {
    DAILY(1),
    WEEKLY(7),
    BIWEEKLY(14),
    MONTHLY(30),
    OCCASIONS(0)
}

@Serializable
enum class ArhamStatus {
    OVERDUE, // أحمر 🔴 >= 1.0
    SOON,    // أصفر 🟡 0.7..<1.0
    OK       // أخضر 🟢 < 0.7
}

@Serializable
enum class KinshipLayer {
    ANCESTOR,
    SELF_BRANCH,
    COLLATERAL_PATERNAL,
    COLLATERAL_MATERNAL,
    SPOUSE_SIDE,
    OTHER
}

@Serializable
enum class Relation(val layer: KinshipLayer) {
    GRANDFATHER(KinshipLayer.ANCESTOR),
    GRANDMOTHER(KinshipLayer.ANCESTOR),
    FATHER(KinshipLayer.ANCESTOR),
    MOTHER(KinshipLayer.ANCESTOR),
    BROTHER(KinshipLayer.SELF_BRANCH),
    SISTER(KinshipLayer.SELF_BRANCH),
    SON(KinshipLayer.SELF_BRANCH),
    DAUGHTER(KinshipLayer.SELF_BRANCH),
    SPOUSE(KinshipLayer.SELF_BRANCH),
    UNCLE_PATERNAL(KinshipLayer.COLLATERAL_PATERNAL),
    AUNT_PATERNAL(KinshipLayer.COLLATERAL_PATERNAL),
    COUSIN_PATERNAL(KinshipLayer.COLLATERAL_PATERNAL),
    UNCLE_MATERNAL(KinshipLayer.COLLATERAL_MATERNAL),
    AUNT_MATERNAL(KinshipLayer.COLLATERAL_MATERNAL),
    COUSIN_MATERNAL(KinshipLayer.COLLATERAL_MATERNAL),
    IN_LAW(KinshipLayer.SPOUSE_SIDE),
    CLOSE_FRIEND(KinshipLayer.OTHER),
    CUSTOM(KinshipLayer.OTHER)
}

@Serializable
enum class NumberUsage {
    PERSONAL,
    WORK,
    BOTH
}

@Serializable
enum class Rating(val value: Int) {
    BAD(1),
    OK(2),
    EXCELLENT(3)
}

@Serializable
enum class DisputeStatus {
    OPEN,
    RESOLVED,
    ARCHIVED
}

@Serializable
enum class AutomationType {
    IN_APP_MESSAGE,
    AUTO_RING
}

@Serializable
enum class MessageDirection {
    SENT,
    RECEIVED
}

@Serializable
enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ
}

@Serializable
enum class RingingStatus {
    IN_PROGRESS,
    ANSWERED,
    STOPPED_BY_USER,
    MAX_REACHED,
    BLOCKED
}

@Serializable
enum class StatStatus {
    NORMAL,
    HIGHER,
    LOWER
}

@Serializable
enum class DashboardPeriod {
    TODAY,
    WEEK,
    MONTH,
    CUSTOM
}

@Serializable
enum class RecurrenceType {
    DAILY,
    WEEKLY,
    MONTHLY,
    CUSTOM,
    ONCE
}

@Serializable
enum class CallDirection {
    IN,
    OUT,
    MISSED
}

@Serializable
enum class CallSource {
    CALL_LOG,
    IN_APP,
    OBSERVER,
    MANUAL
}
