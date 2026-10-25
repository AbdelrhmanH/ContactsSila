package app.sila.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ContactName(
    val displayName: String,
    val fileAs: String = displayName,
    val prefix: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
    val phoneticName: String? = null
)

@Serializable
data class Phone(
    val raw: String,
    val e164: String,
    val type: String = "MOBILE",
    val label: String? = null,
    val isPrimary: Boolean = false,
    val preferredSimSlot: Int? = null
)

@Serializable
data class Email(
    val address: String,
    val type: String = "PERSONAL",
    val label: String? = null
)

@Serializable
data class Address(
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val type: String = "HOME"
)

@Serializable
data class Organization(
    val company: String? = null,
    val department: String? = null,
    val jobTitles: List<String> = emptyList()
)

@Serializable
data class ImportantDate(
    val type: String, // BIRTHDAY, ANNIVERSARY, CUSTOM
    val label: String? = null,
    val month: Int,
    val day: Int,
    val year: Int? = null
)

@Serializable
data class CustomField(
    val label: String,
    val value: String
)

@Serializable
data class ContactAccount(
    val accountType: String?,
    val accountName: String?
)

@Serializable
data class ArhamInfo(
    val relation: Relation,
    val layer: KinshipLayer = relation.layer,
    val parentContactId: Long? = null,
    val frequency: ArhamFrequency,
    val lastContactedAt: Long? = null,
    val pausedUntil: Long? = null
)

@Serializable
data class ServiceRef(
    val cloudId: String,
    val professionId: String,
    val visibility: ServiceVisibility
)

@Serializable
data class Contact(
    val id: Long,
    val systemContactId: String,
    val lookupKey: String? = null,
    val name: ContactName,
    val photoUri: String? = null,
    val phones: List<Phone> = emptyList(),
    val emails: List<Email> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val organization: Organization? = null,
    val dates: List<ImportantDate> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val websites: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val category: Category = Category.UNCATEGORIZED,
    val isFavorite: Boolean = false,
    val ringtoneUri: String? = null,
    val sendToVoicemail: Boolean = false,
    val isBlocked: Boolean = false,
    val notes: String? = null,
    val account: ContactAccount? = null,
    val arham: ArhamInfo? = null,
    val linkedService: ServiceRef? = null,
    val normalizedName: String = name.displayName,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
