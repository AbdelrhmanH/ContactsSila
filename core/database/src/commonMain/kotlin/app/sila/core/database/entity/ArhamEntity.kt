package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation

/**
 * كيان بيانات صلة الرحم المرتبط بجهة اتصال محلية — الصورة الفيزيائية الحرفية لجدول
 * `arham` (DATA_MODEL.md §2.2 · ADR-0011).
 *
 * الجدول الفيزيائي **بلا فهارس ثانوية إطلاقاً** — أُزيل تصريح فهرس `parentContactId`
 * الذي كان تعلنه الشل القديمة ولا وجود له فعلياً (PR B · مطابقة الفيزيائي).
 */
@Entity(tableName = "arham")
data class ArhamEntity(
    @PrimaryKey val contactId: Long,
    val relation: Relation,
    val layer: KinshipLayer,
    val parentContactId: Long? = null,
    val frequency: ArhamFrequency,
    val lastContactedAt: Long? = null,
    val pausedUntil: Long? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
)
