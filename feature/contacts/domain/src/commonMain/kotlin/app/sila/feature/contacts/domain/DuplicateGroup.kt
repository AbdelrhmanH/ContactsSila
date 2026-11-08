package app.sila.feature.contacts.domain

import app.sila.core.model.Contact

/** ثقة مجموعة المكررات (spec 04 §أ: HIGH للهاتف/البريد، MEDIUM للاسم، LOW لاقتراح Jaro-Winkler فقط). */
enum class DuplicateConfidence { HIGH, MEDIUM, LOW }

/** نوع الدليل المُجمِّع — بياناتٌ خام تُعرض عبر عقد الترجمة، لا نثراً مُصلَّباً في النطاق. */
enum class DuplicateReasonKind {
    /** نفس الرقم بعد التطبيع E.164 (أو نفس الرمز القصير). */
    SAME_PHONE,

    /** نفس البريد الإلكتروني بعد lowercase. */
    SAME_EMAIL,

    /** نفس الاسم بعد التطبيع العربي الكامل. */
    SAME_NAME,

    /** تشابه Jaro-Winkler ≥ 0.92 — اقتراح فقط، لا يُدمج تلقائياً أبداً. */
    SIMILAR_NAME
}

/**
 * مجموعة جهات اتصال مكررة مكتشفة محلياً (Issue #28 — spec 04 §أ).
 *
 * - [id] **مشتق من هوية الأعضاء** (`stableSuggestionId(DUPLICATES, sorted systemContactIds)`) —
 *   نفس اشتقاق بطاقات الاقتراح (Issue #24) فلا تنكسر إغلاقات البطاقات القائمة.
 * - [confidence] أعلى ثقة دليل داخل المجموعة (اتحاد الأدلة عبر union-find).
 * - [reasonKind]/[reasonDetail] الدليل المسيطر وقيمته الخام (e164/بريد/اسم مطبَّع) — العرض
 *   عبر موارد الترجمة (قاعدة «لا نصوص ثابتة في النطاق»).
 */
data class DuplicateGroup(
    val id: String,
    val contacts: List<Contact>,
    val confidence: DuplicateConfidence,
    val reasonKind: DuplicateReasonKind,
    val reasonDetail: String,
)
