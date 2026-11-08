package app.sila.feature.contacts.domain

/**
 * عقد قاعدة التصنيف الذكي (T7 · Issue #28 — spec 04 §ب): تقيّم مرشّحاً مجهَّزاً وتُخرج
 * اقتراحاً كامل الثقة والدليل (لا Category? خام — العرض والتغذية الراجعة يحتاجان
 * [CategorizationSuggestion.matchedToken] وagy F6/professionId). تطبيق مبدأ
 * الفتح للإضافة والإغلاق للتعديل (OCP): قواعد جديدة بلا مساس بالمحرك.
 */
fun interface ContactCategorizationRule {
    fun evaluateSuggestion(candidate: SmartFilterCandidate): CategorizationSuggestion?
}
