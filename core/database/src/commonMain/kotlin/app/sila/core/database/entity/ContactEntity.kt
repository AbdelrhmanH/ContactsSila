package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.Category

/**
 * كيان جهة الاتصال في قاعدة البيانات المحلية (DATA_MODEL.md §2.1).
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["systemContactId"], unique = true),
        Index(value = ["category"]),
        Index(value = ["normalizedName"]),
        Index(value = ["isFavorite"])
    ]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val systemContactId: String,
    val lookupKey: String? = null,
    val accountType: String? = null,
    val accountName: String? = null,
    val displayName: String,
    val fileAs: String = displayName,
    val prefix: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
    val phoneticName: String? = null,
    val company: String? = null,
    val department: String? = null,
    val photoUri: String? = null,
    val category: Category = Category.UNCATEGORIZED,
    val isFavorite: Boolean = false,
    val customRingtoneUri: String? = null,
    val sendToVoicemail: Boolean = false,
    val isBlocked: Boolean = false,
    val notes: String? = null,
    val linkedServiceCloudId: String? = null,
    val excludeFromSmartFilter: Boolean = false,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * تعليم حذف ناعم (ADR-0010 §6 — أُضيف في هجرة المرحلة 1 إلى الإصدار 2):
     * طابع زمني لحظة حذف جهة الاتصال من دفتر النظام؛ `null` = حيّة.
     * الصفوف المعلَّمة تُخفى من القائمة وتبقى قابلة للاسترجاع 30 يوماً ثم يُحذفها
     * `DataRetentionCleanupDao.cleanupExpiredData` حذفاً نهائياً.
     * المزامنة وحدها من تكتبه (حذف مؤكد من المنصة/المصالحة الكاملة) — ولا يكتبه سحب الصلاحية
     * ولا `.limited` (ADR-0010 §5) أبداً.
     */
    val systemDeletedAt: Long? = null
)
