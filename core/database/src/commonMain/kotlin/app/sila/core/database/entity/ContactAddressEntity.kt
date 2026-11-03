package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان عنوان بريدي مرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_addresses`
 * (DATA_MODEL.md §2.1 · ADR-0011 · ترحيل v1→v2 — T6/Issue #27).
 *
 * **الملكية (ADR-0012):** بيانات صِلَة-فقط — لا تُقرأ من المنصة (المصدر المجمد T1 لا يحملها)
 * ولا تُكتب إليها؛ المزامنة لا تلمسها أبداً (تُحفظ حرفياً في الدمج كما أعمدة صِلَة).
 */
@Entity(
    tableName = "contact_addresses",
    indices = [
        Index(name = "idx_contact_addresses_cid", value = ["contactId"]),
    ],
)
data class ContactAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    @ColumnInfo(defaultValue = "'HOME'") val type: String = "HOME",
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
