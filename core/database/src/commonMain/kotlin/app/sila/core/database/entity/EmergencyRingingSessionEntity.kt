package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.sila.core.model.RingingStatus

/**
 * كيان جلسات الرنين المتكرر للطوارئ (DATA_MODEL.md §2.8).
 */
@Entity(tableName = "emergency_ringing_sessions")
data class EmergencyRingingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val phoneE164: String,
    val attempts: Int,
    val maxAttempts: Int = 10,
    val status: RingingStatus,
    val startedAt: Long,
    val endedAt: Long? = null
)
