package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان حقل مخصص (تسمية حرة + قيمة) مرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول
 * `contact_custom_fields` (DATA_MODEL.md §2.1 · ADR-0011 · ترحيل v1→v2 — T6/Issue #27).
 *
 * **الملكية (ADR-0012):** بيانات صِلَة-فقط — تُخزن Room حصراً (المواصفة 03 تعطي الخيار
 * «MIME مخصص أو Room»، وقرار T6: Room — بلا MIME خاص يُسرَب إلى دفاتر تطبيقات أخرى).
 */
@Entity(
    tableName = "contact_custom_fields",
    indices = [
        Index(name = "idx_contact_custom_fields_cid", value = ["contactId"]),
    ],
)
data class ContactCustomFieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val label: String,
    val value: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
