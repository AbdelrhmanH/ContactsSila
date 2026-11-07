package app.sila.feature.arham.domain

import app.sila.core.model.ArhamStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation

/**
 * نموذج بيانات شجرة العائلة (T8-07 · AC-13) — نطاقٌ بحت بلا رسم (الرسم T8-11).
 *
 * التوليدات مفاتيحها طبقات الصلة [KinshipLayer] منسوخة على كل `ArhamInfo` — **لا مشى
 * على سلسلة الآباء** عند البناء، فحلقاتُ الآباء لا تُعلّق البناء مطلقاً؛ الوصلات
 * ([ArhamTreeEdge]) بيانات رسمٍ فقط.
 */
data class ArhamFamilyTree(
    val generations: List<ArhamTreeGeneration>,
    val edges: List<ArhamTreeEdge>,
)

/**
 * توليدة واحدة من الشجرة — كل أعضائها من طبقة صلة واحدة، مرتَّبين حتمياً
 * (الاسم المعروض ثم معرف جهة الاتصال).
 */
data class ArhamTreeGeneration(
    val layer: KinshipLayer,
    val nodes: List<ArhamTreeNode>,
)

/** عقدة قريب في الشجرة — حلقة الحالة (🔴/🟡/🟢) مشتقة من الإلحاح عند لحظة البناء. */
data class ArhamTreeNode(
    val contactId: Long,
    val displayName: String,
    val photoUri: String? = null,
    val relation: Relation,
    val layer: KinshipLayer,
    val parentContactId: Long? = null,
    val urgency: Double,
    val status: ArhamStatus,
    val daysSinceLastContact: Int?,
)

/** وصلتُ قرابةٍ ابن←والد للرسم — تُنشأ حين يكون الأبوين عقدةً موجودة في الشجرة. */
data class ArhamTreeEdge(
    val childContactId: Long,
    val parentContactId: Long,
)
