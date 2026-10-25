package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Contact

/**
 * حالة استخدام أحادية المسؤولية: كشف الأسماء والأرقام المكررة محلياً (PRD §7).
 */
class DetectDuplicatesUseCase {

    operator fun invoke(contacts: List<Contact>): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()

        val byPhone = mutableMapOf<String, MutableList<Contact>>()
        for (contact in contacts) {
            for (phone in contact.phones) {
                val e164 = PhoneNormalizer.normalizeToE164(phone.raw)
                if (e164.isNotEmpty()) {
                    byPhone.getOrPut(e164) { mutableListOf() }.add(contact)
                }
            }
        }
        byPhone.forEach { (e164, list) ->
            val distinct = list.distinctBy { it.id }
            if (distinct.size > 1) {
                groups.add(DuplicateGroup(reason = "نفس الرقم ($e164)", contacts = distinct))
            }
        }

        val byNormalizedName = contacts.groupBy { ArabicTextNormalizer.normalize(it.name.displayName) }
        byNormalizedName.forEach { (normName, list) ->
            if (normName.isNotEmpty() && list.size > 1) {
                val alreadyGrouped = groups.any { g ->
                    g.contacts.map { it.id }.toSet() == list.map { it.id }.toSet()
                }
                if (!alreadyGrouped) {
                    groups.add(DuplicateGroup(reason = "تطابق الاسم ($normName)", contacts = list))
                }
            }
        }

        return groups
    }
}
