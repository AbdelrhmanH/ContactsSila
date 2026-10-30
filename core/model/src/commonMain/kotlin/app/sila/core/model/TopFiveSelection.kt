package app.sila.core.model

import kotlinx.serialization.Serializable

/**
 * سجل اختيار من "أهم 5 أشخاص" في التهيئة الأولى (Issue #26 — T5).
 *
 * عقد التسليم إلى T8 (ميزة أرحام): التهيئة تحفظ هذه الاختيارات فقط (via core:datastore)،
 * وعند توفر سجل جهات الاتصال الفعلي (بعد T2) تنشئ T8 سجلات جهات بتصنيف `Category.FAMILY`
 * ومعلومة أرحام `ArhamInfo(relation = relation, frequency = frequency)`.
 * الوتر الافتراضي للتوتّر: `ArhamFrequency.WEEKLY` كما في مواصفات الميزة.
 *
 * ملاحظة الخصوصية: يُودع على الجهاز فقط (local-first)؛ لا كتابة سحابية إطلاقاً.
 */
@Serializable
data class TopFiveSelection(
    val systemContactId: String,
    val displayName: String,
    val relation: Relation,
    val frequency: ArhamFrequency = ArhamFrequency.WEEKLY
)
