package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource

/**
 * كيان سجل أحداث المكالمات المحلي (DATA_MODEL.md §2.3).
 */
@Entity(
    tableName = "call_events",
    indices = [
        Index(value = ["e164"]),
        Index(value = ["timestamp"])
    ]
)
data class CallEventEntity(
    @PrimaryKey val callLogId: String,
    val e164: String,
    val direction: CallDirection,
    val durationSec: Int,
    val timestamp: Long,
    val source: CallSource,
    val simSlot: Int? = null
)
