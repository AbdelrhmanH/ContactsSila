package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة العمل (T7 · spec 04 §ب — جدول الثقة): حقل شركة = LOW (إشارة ضعيفة لا تُحدد
 * مسبقاً)، ولقب مهني في الاسم («مهندس»، «م.») = MEDIUM.
 */
class WorkCategorizationRule : ContactCategorizationRule {

    override fun evaluateSuggestion(candidate: SmartFilterCandidate): CategorizationSuggestion? {
        val titleToken = candidate.nameTokens.firstOrNull { it in SmartFilterDictionary.WORK_TITLE_TOKENS }
        if (titleToken != null) {
            return suggestion(candidate, SmartFilterConfidence.MEDIUM, matchedToken = titleToken)
        }
        if (candidate.hasCompany) {
            return suggestion(candidate, SmartFilterConfidence.LOW, matchedToken = COMPANY_TOKEN)
        }
        return null
    }

    private fun suggestion(
        candidate: SmartFilterCandidate,
        confidence: SmartFilterConfidence,
        matchedToken: String,
    ) = CategorizationSuggestion(
        contactId = candidate.contactId,
        category = Category.WORK,
        confidence = confidence,
        matchedToken = matchedToken,
    )

    private companion object {
        const val COMPANY_TOKEN = "company"
    }
}
