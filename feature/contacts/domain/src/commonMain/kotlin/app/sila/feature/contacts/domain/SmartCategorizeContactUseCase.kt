package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.ArabicTokenizer
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Category

/**
 * حالة استخدام: التصنيف الذكي المحلي لنقطة واحدة (PRD §8 — بلا مستدعين إنتاجيين
 * اليوم؛ أبقيتها كغلاف رقيق فوق محرك القواعد الجديد [ContactCategorizationRule.
 * evaluateSuggestion] ليظل عقد الاقتراح الأول قائماً). المحرك الفعلي للمراجعة
 * الجماعية هو [ObserveSmartFilterSuggestionsUseCase].
 */
class SmartCategorizeContactUseCase(
    private val rules: List<ContactCategorizationRule> = defaultRules(),
) {

    operator fun invoke(displayName: String, primaryPhone: String, hasCompany: Boolean = false): Category {
        val candidate = SmartFilterCandidate(
            contactId = 0L,
            nameTokens = ArabicTokenizer.split(ArabicTextNormalizer.normalize(displayName)),
            e164Phone = PhoneNormalizer.normalizeToE164(primaryPhone),
            hasCompany = hasCompany,
        )
        return rules.mapNotNull { it.evaluateSuggestion(candidate) }
            .minByOrNull { it.confidence.ordinal } // HIGH أدنى ترتيباً — الأعلى ثقةً يفوز، والتعادل لترتيب القواعد
            ?.category
            ?: Category.UNCATEGORIZED
    }

    companion object {
        fun defaultRules(): List<ContactCategorizationRule> = listOf(
            EmergencyCategorizationRule(),
            FamilyCategorizationRule(),
            ServiceCategorizationRule(),
            WorkCategorizationRule(),
        )
    }
}
