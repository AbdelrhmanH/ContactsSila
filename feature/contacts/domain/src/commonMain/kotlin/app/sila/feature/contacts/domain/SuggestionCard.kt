package app.sila.feature.contacts.domain

/**
 * بطاقة اقتراح قابلة للإغلاق في أعلى القائمة (Issue #24 — مكررات / مراجعة فلترة ذكية).
 *
 * - [id] **مشتق من هوية العناصر نفسها** (`systemContactId` مرتّب ومُلخّص) — لذلك
 *   لا تعود البطاقة بعد إغلاقها إلا إذا تغيّرت مجموعة العناصر ("لا تعود لنفس العناصر").
 * - [contactIds] معرّفات العناصر المعنية للعرض/التنقل.
 * - [count] عدد العناصر المعروضة في نص البطاقة؛ التسميات في موارد الترجمة لا هنا.
 */
data class SuggestionCard(
    val id: String,
    val kind: Kind,
    val contactIds: List<Long>,
    val count: Int
) {
    enum class Kind {
        /** مجموعة مكررات محتملة (كشف في النطاق — شاشات الدمج T7). */
        DUPLICATES,

        /** مراجعة فلترة ذكية: جهات غير مصنّفة تنتظر تصنيفاً. */
        SMART_FILTER
    }
}

/** توليد معرّف بطاقة ثابت من هوية عناصرها (نفس العناصر ⇒ نفس المعرّف). */
internal fun stableSuggestionId(kind: SuggestionCard.Kind, identityKeys: List<String>): String {
    val joined = identityKeys.sorted().joinToString(separator = "|")
    return "${kind.name.lowercase()}:${joined.hashCode()}"
}
