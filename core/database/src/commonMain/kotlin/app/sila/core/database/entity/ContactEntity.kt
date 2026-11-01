package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.sila.core.model.Category

/**
 * كيان جهة الاتصال — **الصورة الفيزيائية الحرفية** لجدول `contacts` في قاعدة البيانات
 * المحلية (DATA_MODEL.md §2.1 · ADR-0011).
 *
 * قواعد PR B الصارمة (ADR-0011 §الأعمدة والفهارس):
 * - كل عمود هنا يعكس عموداً فيزيائياً حقيقياً — بلا حقول غير مُخزَّنة (كانت الشل القديمة
 *   تعلن حقولاً لا يملكها الجدول، وهذا ما تصححه PR B).
 * - الفهرس `idx_contacts_system_id` **غير فريد** (non-unique) مطابقةً للفهرس الفعلي الموجود
 *   في قواعد المستخدمين (Decision 2 — لا قيد فريد على مستوى قاعدة البيانات في PR B؛
 *   منع التكرار يبقى على مستوى التطبيق، والقيم المكررة تاريخياً تُحفظ ولا تُمس).
 * - أسماء الفهارس تُصرَّح حرفياً (non-generated prefixes تُقارن بمطابقة تامة في التحقق).
 * - القيم الافتراضية تُصرَّح بمطابقة DDL الفعلي حرفياً (`''` / `0`) ليتطابق مخطط
 *   التثبيت الجديد مع المخطط المعتمَد (adoption) بتساوي تام في Room validation.
 */
@Entity(
    tableName = "contacts",
    indices = [
        // غير فريد عمداً — مطابق للفيزيائي (Decision 2 · ADR-0011).
        Index(name = "idx_contacts_system_id", value = ["systemContactId"]),
        Index(name = "idx_contacts_category", value = ["category"]),
        Index(name = "idx_contacts_norm_name", value = ["normalizedName"]),
        Index(name = "idx_contacts_fav", value = ["isFavorite"]),
    ],
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val systemContactId: String,
    val lookupKey: String? = null,
    val accountType: String? = null,
    val accountName: String? = null,
    val displayName: String,
    @ColumnInfo(defaultValue = "''") val fileAs: String,
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
    val category: Category,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean,
    val customRingtoneUri: String? = null,
    @ColumnInfo(defaultValue = "0") val sendToVoicemail: Boolean,
    @ColumnInfo(defaultValue = "0") val isBlocked: Boolean,
    val notes: String? = null,
    val linkedServiceCloudId: String? = null,
    @ColumnInfo(defaultValue = "0") val excludeFromSmartFilter: Boolean,
    val normalizedName: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
    /**
     * تعليم حذف ناعم (ADR-0010 §6): طابع زمني لحظة حذف جهة الاتصال من دفتر النظام؛
     * `null` = حيّة. الصفوف المعلَّمة تُخفى من القائمة وتبقى قابلة للاسترجاع 30 يوماً
     * ثم يُحذفها التنظيف الدوري نهائياً. المزامنة وحدها تكتبه.
     */
    val systemDeletedAt: Long? = null,
)
