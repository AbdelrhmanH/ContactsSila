package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة الخدمات (T7 · spec 04 §ب + agy F6): مصطلح خدمة من القاموس = HIGH مع
 * [CategorizationSuggestion.professionId] مستقراً؛ الرمز القصير/الخط الساخن والأرضي
 * المصري المعلوم = LOW (إشارة ضعيفة لا تُحدد مسبقاً — جدول الثقة في المواصفة).
 */
class ServiceCategorizationRule : ContactCategorizationRule {

    override fun evaluateSuggestion(candidate: SmartFilterCandidate): CategorizationSuggestion? {
        val term = SmartFilterDictionary.firstMatch(SmartFilterDictionary.SERVICE_TERMS, candidate.nameTokens)
        if (term != null) {
            return CategorizationSuggestion(
                contactId = candidate.contactId,
                category = Category.SERVICES,
                confidence = SmartFilterConfidence.HIGH,
                professionId = SmartFilterDictionary.SERVICE_TERMS.getValue(term),
                matchedToken = term,
            )
        }
        if (isEgyptianLandline(candidate.e164Phone)) {
            return CategorizationSuggestion(
                contactId = candidate.contactId,
                category = Category.SERVICES,
                confidence = SmartFilterConfidence.LOW,
                matchedToken = LANDLINE_TOKEN,
            )
        }
        return null
    }

    /**
     * أرضي مصري محافظ: +20 ووطني 9–10 خانات يبدأ 2–5 (بادئات المحافظات بعد سقوط الصفر
     * في E.164). موثق في تقرير T7 — خليجي/مغترب خارج الحكم عمداً (تجنب إنكارات زائفة).
     */
    private fun isEgyptianLandline(e164: String?): Boolean {
        val digits = e164?.removePrefix("+")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return false
        if (!digits.startsWith(EGYPT_CC)) return false
        val national = digits.removePrefix(EGYPT_CC)
        return national.length in 9..10 && national.first() in '2'..'5'
    }

    private companion object {
        const val EGYPT_CC = "20"
        const val LANDLINE_TOKEN = "landline"
    }
}
