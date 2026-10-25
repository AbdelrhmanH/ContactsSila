package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة تصنيف أرقام وكلمات الطوارئ (OCP & SRP).
 */
class EmergencyCategorizationRule : ContactCategorizationRule {
    private val emergencyCodes = setOf("122", "123", "180", "911", "999")
    private val keywords = setOf("اسعاف", "شرطه", "مطافي", "نجده", "طواري", "emergency")

    override fun evaluate(normalizedName: String, rawPhone: String, e164Phone: String, hasCompany: Boolean): Category? =
        if (e164Phone in emergencyCodes || keywords.any { normalizedName.contains(it) }) Category.EMERGENCY else null
}
