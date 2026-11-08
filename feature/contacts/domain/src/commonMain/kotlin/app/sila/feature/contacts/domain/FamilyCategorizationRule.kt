package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة العائلة (T7 · spec 04 §ب): مصطلح قرابة من القاموس المضمّن = HIGH مع مرشّح
 * القرابة من [SmartFilterDictionary.FAMILY_TERMS] — يُقدَّم لاحقاً لجسر أرحام (S10)
 * بلا اقتران ميزة (الفصل عقد agy F5). المطابقة رمز-كامل بلا substring.
 */
class FamilyCategorizationRule : ContactCategorizationRule {

    override fun evaluateSuggestion(candidate: SmartFilterCandidate): CategorizationSuggestion? {
        val tokens = candidate.nameTokens
        // العبارات المركّبة («ابن عمي») أكثر تحديداً من مفرداتها («عمي»/«عم») — أسبقية حتمية
        for ((term, relation) in SmartFilterDictionary.FAMILY_TERMS) {
            if (' ' in term && SmartFilterDictionary.termMatches(tokens, term)) {
                return suggestion(candidate, term, relation)
            }
        }
        for ((term, relation) in SmartFilterDictionary.FAMILY_TERMS) {
            if (' ' !in term && SmartFilterDictionary.termMatches(tokens, term)) {
                return suggestion(candidate, term, relation)
            }
        }
        return null
    }

    private fun suggestion(
        candidate: SmartFilterCandidate,
        term: String,
        relation: app.sila.core.model.Relation,
    ) = CategorizationSuggestion(
        contactId = candidate.contactId,
        category = Category.FAMILY,
        confidence = SmartFilterConfidence.HIGH,
        relationCandidate = relation,
        matchedToken = term,
    )
}
