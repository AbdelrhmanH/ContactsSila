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
    BLOCKED,

    /**
     * جلسة أُغلقت تسويةً بعد موت العملية (T8-10 · §N.5/§O.7): «أُغلقت كـ interrupted
     * والحد اليومي سليم» — قيمة إضافية مُصاحَحة (انحراف مُفصَح عن D4-14 الذي زعم أن
     * القيم الخمس تكفي؛ النص السلوكي §N.5 هو المرجع). محوّل TEXT `.name` round-trip
     * متوافق رجوعياً — بلا تغيير مخطط.
     */
    INTERRUPTED
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

/**
 * منشأ الدليل لحدث المكالمة (ADR-0006 §عقد `CallEventSource`) — تمثيلٌ للمصدر لا ضماناً
 * بأن كل مصدر يوفر كل الحقول.
 *
 * قِيَم `CALL_LOG` و`OBSERVER` قِيَم إرثية من المرحلة 0: تُحفظ في البيانات القديمة
 * وتدور عبر `valueOf` أثناء الهجرة (خطة T8 §G.3) ولا يكتبها أي مسار جديد.
 */
@Serializable
enum class CallSource {
    /** إرثي: سجل اتصال النظام — القراءة منه ممنوعة (`READ_CALL_LOG`)، احتُفظ بالقيمة للبيانات القديمة فقط. */
    @Deprecated("قيمة إرثية للبيانات القديمة فقط — لا يكتبها أي مسار جديد (ADR-0006)", level = DeprecationLevel.WARNING)
    CALL_LOG,

    /** مكالمة بدأتها صِلَة عبر نية اتصال من داخل التطبيق. */
    IN_APP,

    /** إرثي: مراقب المرحلة 0 العام — استُبدل بمصادر المراقبة المسماة أدناه. */
    @Deprecated("قيمة إرثية للبيانات القديمة فقط — استُبدلت بمصادر المراقبة المسماة (ADR-0006)", level = DeprecationLevel.WARNING)
    OBSERVER,

    /** إدخال/تأكيد المستخدم اليدوي. */
    MANUAL,

    /** دور فرز المكالمات الاختياري على Android (`CallScreeningService` بعد `ROLE_CALL_SCREENING`). */
    ANDROID_CALL_SCREENING,

    /** مراقب حالة الهاتف على Android (`TelephonyCallback`/`PhoneStateListener`) — حالة فقط بلا هوية. */
    ANDROID_TELEPHONY_OBSERVER,

    /** مراقب المكالمات على iOS (`CXCallObserver`) ضمن حياة التطبيق فقط. */
    IOS_CALL_OBSERVER
}
