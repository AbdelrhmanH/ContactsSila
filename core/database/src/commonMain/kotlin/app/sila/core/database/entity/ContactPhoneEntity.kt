package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان رقم الهاتف المرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_phones`
 * (DATA_MODEL.md §2.1 · ADR-0011).
 *
 * حُذفت الحقول غير المُخزَّنة فيزيائياً (`label`, `preferredSimSlot`) التي كانت تعلنها
 * الشل القديمة — المخطط الفيزيائي هو المرجع (PR B · Decision 1 في ADR-0011).
 */
@Entity(
    tableName = "contact_phones",
    indices = [
        Index(name = "idx_contact_phones_cid", value = ["contactId"]),
        Index(name = "idx_contact_phones_e164", value = ["e164"]),
    ],
)
data class ContactPhoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val raw: String,
    val e164: String,
    @ColumnInfo(defaultValue = "'MOBILE'") val type: String = "MOBILE",
    @ColumnInfo(defaultValue = "0") val isPrimary: Boolean = false,
)
