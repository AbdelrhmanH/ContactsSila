package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * قاعدة تصنيف أسماء وألقاب العائلة والأقارب (OCP & SRP).
 */
class FamilyCategorizationRule : ContactCategorizationRule {
    private val keywords = setOf(
        "امي", "ابي", "بابا", "ماما", "عمي", "خالي", "عمتي", "خالتي",
        "جدي", "جدتي", "تيتا", "اخي", "اختي", "زوجتي", "زوجي", "ابن عمي", "العم", "الخاله"
    )

    override fun evaluate(normalizedName: String, rawPhone: String, e164Phone: String, hasCompany: Boolean): Category? =
        if (keywords.any { normalizedName.contains(it) }) Category.FAMILY else null
}
