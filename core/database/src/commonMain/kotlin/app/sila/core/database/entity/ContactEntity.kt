package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.Category

/**
 * كيان جهة الاتصال في قاعدة البيانات المحلية (DATA_MODEL.md §2.1).
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["systemContactId"], unique = true),
        Index(value = ["category"]),
        Index(value = ["normalizedName"]),
        Index(value = ["isFavorite"])
    ]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val systemContactId: String,
    val lookupKey: String? = null,
    val accountType: String? = null,
    val accountName: String? = null,
    val displayName: String,
    val fileAs: String = displayName,
    val prefix: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
    val phoneticName: String? = null,
    val company: String? = null,
    val department: String? = null,
    val photoUri: String? = null,
    val category: Category = Category.UNCATEGORIZED,
    val isFavorite: Boolean = false,
    val customRingtoneUri: String? = null,
    val sendToVoicemail: Boolean = false,
    val isBlocked: Boolean = false,
    val notes: String? = null,
    val linkedServiceCloudId: String? = null,
    val excludeFromSmartFilter: Boolean = false,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long
)
