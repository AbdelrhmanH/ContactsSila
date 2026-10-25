package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة تصنيف جهات اتصال العمل والشركات (OCP & SRP).
 */
class WorkCategorizationRule : ContactCategorizationRule {
    override fun evaluate(normalizedName: String, rawPhone: String, e164Phone: String, hasCompany: Boolean): Category? =
        if (hasCompany || normalizedName.startsWith("م.") || normalizedName.startsWith("مهندس ")) {
            Category.WORK
        } else {
            null
        }
}
