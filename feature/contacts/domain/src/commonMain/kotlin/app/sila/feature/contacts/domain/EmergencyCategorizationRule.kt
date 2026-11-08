package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة الطوارئ (T7 · spec 04 §ب — جدول الثقة): كلمة طوارئ في الاسم أو **رمز قصير
 * معروف** في الرقم = HIGH حصراً. الرموز تُقارن على الأرقام كاملةً بشرط القِصر (≤5)
 * فلا يطابق رقم شخصي طويلاً لاحقة رمز (حارس [SmartFilterDictionary.EMERGENCY_CODES]).
 */
class EmergencyCategorizationRule : ContactCategorizationRule {

    override fun evaluateSuggestion(candidate: SmartFilterCandidate): CategorizationSuggestion? {
        val keyword = SmartFilterDictionary.EMERGENCY_TERMS.firstOrNull { term ->
            SmartFilterDictionary.termMatches(candidate.nameTokens, term)
        }
        if (keyword != null) {
            return suggestion(candidate, matchedToken = keyword)
        }
        val code = emergencyCodeOf(candidate.e164Phone) ?: return null
        return suggestion(candidate, matchedToken = code)
    }

    private fun suggestion(candidate: SmartFilterCandidate, matchedToken: String) = CategorizationSuggestion(
        contactId = candidate.contactId,
        category = Category.EMERGENCY,
        confidence = SmartFilterConfidence.HIGH,
        matchedToken = matchedToken,
    )

    private fun emergencyCodeOf(e164: String?): String? {
        val digits = e164?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() } ?: return null
        if (digits.length > MAX_EMERGENCY_DIGITS) return null
        return SmartFilterDictionary.EMERGENCY_CODES.firstOrNull { code ->
            digits == code || digits.endsWith(code)
        }
    }

    private companion object {
        const val MAX_EMERGENCY_DIGITS = 5
    }
}
