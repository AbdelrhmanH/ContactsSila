package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.sila.core.model.MessageDirection
import app.sila.core.model.MessageStatus

/**
 * كيان صندوق رسائل أرحام المتبادلة — الصورة الفيزيائية الحرفية لجدول `arham_inbox`
 * بـ 8 أعمدة (DATA_MODEL.md §2.7 · ADR-0011).
 *
 * حُذفت الحقول غير المُخزَّنة فيزيائياً (`cloudMessageId`, `reaction`, `readAt`) وفهرس
 * `cloudMessageId` الفريد غير الموجود — الجدول الفيزيائي يحمل فهرساً واحداً على
 * `sentAt` (PR B · مطابقة الفيزيائي).
 */
@Entity(
    tableName = "arham_inbox",
    indices = [
        Index(name = "idx_arham_inbox_sent", value = ["sentAt"]),
    ],
)
data class ArhamInboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val senderUid: String,
    val senderName: String,
    val recipientUid: String,
    val messageText: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val sentAt: Long,
)
