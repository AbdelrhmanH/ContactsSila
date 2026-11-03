package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان رقم الهاتف المرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_phones`
 * (DATA_MODEL.md §2.1 · ADR-0011).
 *
 * حُذفت الحقول غير المُخزَّنة فيزيائياً (`preferredSimSlot`) التي كانت تعلنها
 * الشل القديمة — المخطط الفيزيائي هو المرجع (PR B · Decision 1 في ADR-0011).
 *
 * T6 (ترحيل v1→v2 · Issue #27): أُضيفا عمودا `label` (تسمية مخصصة — AC 8 «تُسمَّى») و
 * `sortOrder` (ترتيب داخل المجموعة — AC 8 «تُرتَّب») عبر `ALTER TABLE ADD COLUMN` غير
 * هدمي. **الملكية (ADR-0012):** `label`/`sortOrder` بيانات صِلَة-فقط (لا round-trip
 * منصوي) — المزامنة تحفظهما حرفياً في الدمج.
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
    val label: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
