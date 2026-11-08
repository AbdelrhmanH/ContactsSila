package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * تعلّم محلي من قبول/رفض اقتراحات الفلترة الذكية (T7 — Issue #28 · spec 04 §ب سطر 60:
 * «رفض المستخدم لاقتراح (token→category) يقلل وزنه؛ القبول يزيده (`smart_filter_feedback`)»).
 *
 * - [token] رمز الاسم المطبَّع (ما-بعد-[app.sila.core.common.ArabicTextNormalizer.normalize] —
 *   العرف الموثق: مصطلحات القاموس تُكتب بهذه الصيغة).
 * - [suggestedCategory] اسم التعداد `Category` (valueOf فاشل-الإغلاق — محولات النوع).
 * - المفتاح الأساسي المركب (token, suggestedCategory) حتمي ويوفر التفرد طبيعياً (agy F1 —
 *   لا فهرس فريد إضافي فوق المفتاح ذاته).
 */
@Entity(
    tableName = "smart_filter_feedback",
    primaryKeys = ["token", "suggestedCategory"],
    indices = [Index(name = "idx_smart_filter_feedback_token", value = ["token"])],
)
data class SmartFilterFeedbackEntity(
    val token: String,
    val suggestedCategory: String,
    @ColumnInfo(defaultValue = "0") val acceptedCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val rejectedCount: Int = 0,
)
