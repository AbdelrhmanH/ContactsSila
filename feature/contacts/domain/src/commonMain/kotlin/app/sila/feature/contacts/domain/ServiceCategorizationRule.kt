package app.sila.feature.contacts.domain

import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Category

/**
 * قاعدة تصنيف مقدمي الخدمات والخطوط الساخنة (OCP & SRP).
 */
class ServiceCategorizationRule : ContactCategorizationRule {
    private val keywords = setOf(
        "سباك", "كهربائي", "صيدليه", "دكتور", "عياده", "مطعم", "سوبر ماركت",
        "ورشه", "نجار", "تكييف", "صيانه", "شركه", "توصيل", "دراي كلين", "مغسله", "pharmacy", "dr"
    )

    override fun evaluate(normalizedName: String, rawPhone: String, e164Phone: String, hasCompany: Boolean): Category? =
        if (PhoneNormalizer.isShortCodeOrHotline(rawPhone) || keywords.any { normalizedName.contains(it) }) {
            Category.SERVICES
        } else {
            null
        }
}
