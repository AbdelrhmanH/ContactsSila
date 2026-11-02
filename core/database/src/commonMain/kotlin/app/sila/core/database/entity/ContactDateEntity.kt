package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان تاريخ هام مرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_dates`
 * (DATA_MODEL.md §2.1 · ADR-0011 · ترحيل v1→v2 — T6/Issue #27).
 *
 * **الملكية (ADR-0012):** بيانات صِلَة-فقط — الميلاد/الذكرى/مخصص تُخزن هنا حصراً ولا تُكتب
 * إلى دفتر الهاتف ولا تُقرأ منه (لا round-trip في المصدر المجمد T1). عام `year` اختياري
 * (مواليد بلا سنة شائعة في دفاتر الهاتف).
 *
 * عيد ميلاد جهة **عائلة** يُشتق منه حدث أرحام وقت القراءة (قرار T6 · J-4 — لا جدول مناسبات
 * مستقل؛ T8 مالك مخطط أرحام).
 */
@Entity(
    tableName = "contact_dates",
    indices = [
        Index(name = "idx_contact_dates_cid", value = ["contactId"]),
    ],
)
data class ContactDateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** BIRTHDAY / ANNIVERSARY / CUSTOM — نوع `ImportantDate` في النطاق. */
    val type: String,
    val label: String? = null,
    val month: Int,
    val day: Int,
    val year: Int? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
