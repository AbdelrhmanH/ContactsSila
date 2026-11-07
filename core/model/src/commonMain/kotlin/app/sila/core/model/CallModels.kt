package app.sila.core.model

import kotlinx.serialization.Serializable

/**
 * تفاعل واحد (حدث مكالمة) لأغراض العرض — «التفاعلات الأخيرة» في شاشة تفاصيل جهة
 * الاتصال (T6 · Issue #27). مرآة قراءة لصف `call_events` (Room SSOT) بلا كيانات
 * قاعدة بيانات في عقود النطاق (نمط ADR-0011: العقد النطاقي بلا شروح Room).
 */
@Serializable
data class RecentInteraction(
    val callLogId: String,
    val e164: String,
    val timestampMillis: Long,
    val direction: CallDirection,
    val durationSec: Int,
    val source: CallSource,
)

/**
 * نتيجة المكالمة المرصودة (ADR-0006 §1).
 *
 * - `ATTEMPTED`: ثُبتت نية الاتصال فقط (نية اتصال من التطبيق).
 * - `CONNECTED`: اتصال مثبت — ولا يكفي وحده لتحديث أرحام.
 * - `COMPLETED`: اكتمال مثبت (اتصال + نهاية + مدة > 0) أو تأكيد صريح للمستخدم.
 * - `UNANSWERED`: لم يُرد على المكالمة.
 * - `UNKNOWN`: النتيجة غير معروفة (الافتراضي).
 */
@Serializable
enum class CallOutcome {
    ATTEMPTED,
    CONNECTED,
    COMPLETED,
    UNANSWERED,
    UNKNOWN
}

/**
 * كيفية ربط الحدث بجهة اتصال (ADR-0006 §دورة حياة — خطوة «مرتبط»):
 * تلقائياً بمطابقة رقم من سياق التطبيق، أو باختيار المستخدم، أو غير مرتبط بعد.
 */
@Serializable
enum class CallAssociation {
    UNASSOCIATED,
    AUTO,
    MANUAL
}

/**
 * حالة التأكيد الاحتياطي (ADR-0006 §5):
 * `NOT_REQUIRED` لصفوف الإرجاع القديمة المهاجرة، `PENDING` عند نقص الدليل،
 * `CONFIRMED` بعد تأكيد المستخدم الصريح، و`REJECTED` عند وسم الحدث «غير متعلقة».
 */
@Serializable
enum class CallConfirmation {
    NOT_REQUIRED,
    PENDING,
    CONFIRMED,
    REJECTED
}

/**
 * قوة الدليل وراء الحدث (ADR-0006 §حقول الحدث)؛
 * `USER_CONFIRMED` يعني أن المستخدم هو مصدر اليقين لا المنصة.
 */
@Serializable
enum class CallConfidence {
    HIGH,
    MEDIUM,
    LOW,
    USER_CONFIRMED
}

/**
 * حدث مكالمة موحّد بعقد الحقول السبعة عشر الكامل (ADR-0006 §حقول الحدث).
 *
 * مرآة نطاق بحتة بلا شروح Room (نمط ADR-0011): كل قيمة فارغة تعني
 * «المنصة لم تعطِ هذا الدليل» — لا يُستنتج ولا يُخترع.
 *
 * @param id              معرف محلي ثابت للحدث (مفتاح أساسي في القاعدة).
 * @param primarySource   مصدر الرصد الأول — يُحفظ ولا يُستبدل عند الدمج.
 * @param evidenceSources كل المصادر التي ساهمت في الحدث، وتشمل `MANUAL` عند التأكيد.
 * @param timestamp       وقت أول رصد (فهرس زمني) — عند الدمج يُحتفظ بالأقدم.
 * @param remoteNumber    `null` = المنصة لم تعطِ رقماً؛ لا يُستنتج ولا يُخترع.
 * @param contactId       `null` = لم تُربط جهة اتصال بعد.
 * @param direction       اتجاه المكالمة؛ `null` = غير معروف. قيمة `MISSED` حصرية لسجل
 *                        الاتصال القديم ولا تُكتب هنا أبداً (إشارة «لم يُرد» تُصنّف
 *                        باتجاه فارغ ونتيجة `UNANSWERED`).
 * @param startedAt       `null` = البداية غير معروفة.
 * @param connectedAt     `null` = لم يُرصد اتصال؛ وجوده لا يعني النهاية.
 * @param endedAt         `null` = النهاية غير مرصودة.
 * @param durationSec     `null` = مدة غير موثوقة؛ لا تُشتق من `connectedAt` وحده.
 * @param outcome         نتيجة الحدث (الافتراضي `UNKNOWN`).
 * @param association     حالة الربط بجهة الاتصال (الافتراضي `UNASSOCIATED`).
 * @param confirmation    حالة التأكيد (الافتراضي `PENDING` عند نقص الدليل).
 * @param confidence      قوة الدليل.
 * @param dedupeKey       مفتاح دمج غير حساس اختياري؛ `null` إذا تعذّر توليده.
 * @param simSlot         شريحة الهاتف إن عُرفت؛ `null` = غير معروف.
 */
@Serializable
data class CallEvent(
    val id: String,
    val primarySource: CallSource,
    val evidenceSources: Set<CallSource>,
    val timestamp: Long,
    val remoteNumber: String? = null,
    val contactId: Long? = null,
    val direction: CallDirection? = null,
    val startedAt: Long? = null,
    val connectedAt: Long? = null,
    val endedAt: Long? = null,
    val durationSec: Int? = null,
    val outcome: CallOutcome = CallOutcome.UNKNOWN,
    val association: CallAssociation = CallAssociation.UNASSOCIATED,
    val confirmation: CallConfirmation = CallConfirmation.PENDING,
    val confidence: CallConfidence = CallConfidence.LOW,
    val dedupeKey: String? = null,
    val simSlot: Int? = null,
)
