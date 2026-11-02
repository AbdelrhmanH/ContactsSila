package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * كيان موقع/رابط مرتبط بجهة اتصال — الصورة الفيزيائية الحرفية لجدول `contact_websites`
 * (DATA_MODEL.md §2.1 · ADR-0011 · ترحيل v1→v2 — T6/Issue #27).
 *
 * **الملكية (ADR-0012):** أعمدة معكوسة من المنصة (Data `Website` أندرويداً / `urlAddresses`
 * على iOS — `PlatformContact.websites` يحملها أصلاً) — تعيد المزامنة كتابتها في كل دلتا.
 */
@Entity(
    tableName = "contact_websites",
    indices = [
        Index(name = "idx_contact_websites_cid", value = ["contactId"]),
    ],
)
data class ContactWebsiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val url: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
