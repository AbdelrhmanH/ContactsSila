package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.AutomationType

/**
 * كيان الرسائل والتذكيرات المجدولة (DATA_MODEL.md §2.6).
 */
@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["nextFireAt"])]
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val type: AutomationType,
    val messageText: String? = null,
    val recurrenceJson: String,
    val nextFireAt: Long,
    val isEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)
