package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان بريد إلكتروني مرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_emails`
 * (DATA_MODEL.md §2.1 · ADR-0011 · ترحيل v1→v2 — T6/Issue #27).
 *
 * قواعد ADR-0011 نفسها: لا قيود FK فيزيائية (توثيقية فقط — السلامة المرجعية داخل معاملات
 * الكتابة المركّبة)، والفهارس أسماؤها مُصرَّح بها حرفياً.
 *
 * **الملكية (ADR-0012):** أعمدة معكوسة من المنصة (Data `Email` أندرويداً / `emailAddresses`
 * على iOS) — تعيد المزامنة كتابتها من دفتر الهاتف في كل دلتا.
 */
@Entity(
    tableName = "contact_emails",
    indices = [
        Index(name = "idx_contact_emails_cid", value = ["contactId"]),
    ],
)
data class ContactEmailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val address: String,
    @ColumnInfo(defaultValue = "'PERSONAL'") val type: String = "PERSONAL",
    /** تسمية مخصصة (iOS CNLabeledValue) — بلا قيمة افتراضية (TEXT قابل للفراغ). */
    val label: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
