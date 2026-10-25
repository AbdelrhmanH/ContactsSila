package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.sila.core.model.DisputeStatus

/**
 * كيان سجل النزاعات والشكاوى على الخدمات (DATA_MODEL.md §2.5).
 */
@Entity(tableName = "dispute_logs")
data class DisputeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serviceCloudId: String,
    val description: String,
    val imageUrisJson: String? = null,
    val cloudImageUrlsJson: String? = null,
    val status: DisputeStatus = DisputeStatus.OPEN,
    val cloudDisputeId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long? = null
)
