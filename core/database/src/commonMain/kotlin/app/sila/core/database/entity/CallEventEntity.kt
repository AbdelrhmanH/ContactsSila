package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource

/**
 * كيان سجل أحداث المكالمات المحلي — الصورة الفيزيائية الحرفية لجدول `call_events`
 * بـ 6 أعمدة (DATA_MODEL.md §2.3 · ADR-0011).
 *
 * الجدول الفيزيائي يحمل فهرساً واحداً حصراً على `timestamp` — أُزيل حقل `simSlot`
 * وفهرس `e164` غير الموجودين فيزيائياً (PR B · مطابقة الفيزيائي).
 */
@Entity(
    tableName = "call_events",
    indices = [
        Index(name = "idx_call_events_ts", value = ["timestamp"]),
    ],
)
data class CallEventEntity(
    @PrimaryKey val callLogId: String,
    val e164: String,
    val direction: CallDirection,
    val durationSec: Int,
    val timestamp: Long,
    val source: CallSource,
)
