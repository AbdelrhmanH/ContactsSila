package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Category

/**
 * حالة استخدام: التصنيف الذكي المحلي للأرقام والأسماء (PRD §8).
 * مفتوحة لإضافة قواعد جديدة عبر حقن قائمة ContactCategorizationRule (OCP).
 */
class SmartCategorizeContactUseCase(
    private val rules: List<ContactCategorizationRule> = defaultRules()
) {
    operator fun invoke(displayName: String, primaryPhone: String, hasCompany: Boolean = false): Category {
        val normalized = ArabicTextNormalizer.normalize(displayName)
        val e164 = PhoneNormalizer.normalizeToE164(primaryPhone)

        return rules.firstNotNullOfOrNull { rule ->
            rule.evaluate(
                normalizedName = normalized,
                rawPhone = primaryPhone,
                e164Phone = e164,
                hasCompany = hasCompany
            )
        } ?: Category.UNCATEGORIZED
    }

    companion object {
        fun defaultRules(): List<ContactCategorizationRule> = listOf(
            EmergencyCategorizationRule(),
            FamilyCategorizationRule(),
            ServiceCategorizationRule(),
            WorkCategorizationRule()
        )
    }
}
