package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * كيان رقم الهاتف المرتبط بجهة اتصال (DATA_MODEL.md §2.1).
 */
@Entity(
    tableName = "contact_phones",
    indices = [
        Index(value = ["contactId"]),
        Index(value = ["e164"])
    ]
)
data class ContactPhoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val raw: String,
    val e164: String,
    val type: String = "MOBILE",
    val label: String? = null,
    val isPrimary: Boolean = false,
    val preferredSimSlot: Int? = null
)
