package app.sila.feature.contacts.domain

import app.sila.core.model.Contact

/**
 * مجموعة جهات اتصال مكررة مكتشفة محلياً (PRD §7).
 */
data class DuplicateGroup(
    val reason: String,
    val contacts: List<Contact>
)
