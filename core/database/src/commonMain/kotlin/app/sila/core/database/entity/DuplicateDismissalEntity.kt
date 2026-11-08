package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * استبعاد زوج مكررات بقرار المستخدم «ليست مكررات» (T7 — Issue #28 · spec 04 §أ سطر 15 —
 * جدول محجوز للميزة في ADR-0012 §9).
 *
 * [groupKey] مفتاح ثنائي حتمي = نفس اشتقاق معرف بطاقة الاقتراح الثنائية
 * (`stableSuggestionId(DUPLICATES, sorted systemContactIds)`) — به تُبنى حواف الكشف بحيث
 * لا ينشئ الزوج المرفوض حافةً أصلاً («الزوج المرفوض لا يعود»)، بينما الدليل الجديد عبر
 * جهة ثالثة يبقى مشروعاً. الثنائية على مستوى `systemContactId` (هوية ADR-0009 الدائمة)
 * لا على مستوى `id` المحلي. الانفراد يُدار في طبقة التطبيق داخل معاملة الكتابة
 * (لا فهرس فريد فيزيائياً — ADR-0011 · Decision 5).
 *
 * الفرق الموثق عن `dismissed_suggestion_ids` (DataStore — T3): ذلك استبعاد بطاقة عرض
 * (تفضيل واجهة)، وهذا استبعاد كشفٍ دائم مرتبط بهوية جهات — لذا Room (ADR-0004).
 */
@Entity(
    tableName = "duplicate_dismissals",
    indices = [Index(name = "idx_duplicate_dismissals_groupKey", value = ["groupKey"])],
)
data class DuplicateDismissalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val groupKey: String,
    val reason: String,
    val createdAt: Long,
) {
    companion object {
    }
}
