package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.MessageDirection
import app.sila.core.model.MessageStatus

/**
 * كيان صندوق رسائل أرحام المتبادلة (DATA_MODEL.md §2.7).
 */
@Entity(
    tableName = "arham_inbox",
    indices = [
        Index(value = ["cloudMessageId"], unique = true),
        Index(value = ["sentAt"])
    ]
)
data class ArhamInboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val cloudMessageId: String? = null,
    val senderUid: String,
    val senderName: String,
    val recipientUid: String,
    val messageText: String,
    val reaction: String? = null,
    val direction: MessageDirection,
    val status: MessageStatus,
    val sentAt: Long,
    val readAt: Long? = null
)
