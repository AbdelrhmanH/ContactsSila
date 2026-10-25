package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation

/**
 * كيان بيانات صلة الرحم المرتبط بجهة اتصال محلية (DATA_MODEL.md §2.2).
 */
@Entity(
    tableName = "arham",
    indices = [Index(value = ["parentContactId"])]
)
data class ArhamEntity(
    @PrimaryKey val contactId: Long,
    val relation: Relation,
    val layer: KinshipLayer,
    val parentContactId: Long? = null,
    val frequency: ArhamFrequency,
    val lastContactedAt: Long? = null,
    val pausedUntil: Long? = null,
    val createdAt: Long
)
