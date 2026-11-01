package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import app.sila.core.model.DisputeStatus

/**
 * كيان سجل النزاعات والشكاوى على الخدمات — الصورة الفيزيائية الحرفية لجدول
 * `dispute_logs` بـ 7 أعمدة وبلا فهارس (DATA_MODEL.md §2.5 · ADR-0011).
 *
 * حُذفت الحقول غير المُخزَّنة فيزيائياً (`imageUrisJson`, `cloudImageUrlsJson`,
 * `cloudDisputeId`) التي كانت تعلنها الشل القديمة (PR B · مطابقة الفيزيائي).
 */
@Entity(tableName = "dispute_logs")
data class DisputeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serviceCloudId: String,
    val description: String,
    val status: DisputeStatus = DisputeStatus.OPEN,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long? = null,
)
