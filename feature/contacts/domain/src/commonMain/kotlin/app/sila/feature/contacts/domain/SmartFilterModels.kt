package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Relation

/**
 * سلّم ثقة اقتراح الفلترة الذكية (T7 · Issue #28 — spec 04 §ب): HIGH يُحدَّد مسبقاً
 * في شاشة المراجعة، MEDIUM/LOW اختيارية — وLOW اقتراح خفيف لا يُطبق جماعياً أبداً.
 */
enum class SmartFilterConfidence { HIGH, MEDIUM, LOW }

/**
 * مرشّح التصنيف الذكي — جهة واحدة مُجهَّزة للتقييم (تطبيع + توكنة تتم مرة واحدة في
 * حالة الاستخدام المُلاحِظة، لا في كل قاعدة). [nameTokens] بعد [app.sila.core.common.
 * ArabicTextNormalizer.normalize] + [app.sila.core.common.ArabicTokenizer.split]
 * (اقتطاع أدوات ال التعريف وو العطف) — وقاموس القواعد مكتوب بالصيغة نفسها (عرف موثق).
 */
data class SmartFilterCandidate(
    val contactId: Long,
    val nameTokens: List<String>,
    val e164Phone: String?,
    val hasCompany: Boolean,
)

/**
 * ناتج قاعدة تصنيف (T7 · spec 04 §ب + agy F6): التصنيف المقترح مع سلّم الثقة و
 * [relationCandidate] لجسر أرحام عند مرشّح قرابة، و[professionId] لمقدمي الخدمات
 * (spec 04:51 — SERVICES يحمل معرّف مهنة مستقراً)، و[matchedToken] الدليل المطابق —
 * وهو مفتاح التغذية الراجعة المحلية (`smart_filter_feedback` زوج token+category).
 */
data class CategorizationSuggestion(
    val contactId: Long,
    val category: Category,
    val confidence: SmartFilterConfidence,
    val relationCandidate: Relation? = null,
    val professionId: String? = null,
    val matchedToken: String,
)

/** بند اقتراح جاهز للعرض والتطبيق — التصنيف داخل البند (تُجمَّع المجموعات منه). */
data class SmartFilterItem(
    val contactId: Long,
    val displayName: String,
    val category: Category,
    val confidence: SmartFilterConfidence,
    val matchedToken: String,
    val professionId: String? = null,
    val relationCandidate: Relation? = null,
)

/** مجموعة اقتراحات بتصنيف واحد — وحدة العرض في شاشة المراجعة (ترتيب حتمي). */
data class SmartFilterCategoryGroup(
    val category: Category,
    val items: List<SmartFilterItem>,
)
