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
