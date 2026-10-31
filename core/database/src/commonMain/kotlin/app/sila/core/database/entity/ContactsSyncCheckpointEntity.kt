package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * صف تخزين نقطة استئناف المزامنة التزايدية لجهات الاتصال (Issue #23 · ADR-0010 §4/§8).
 *
 * جدول مفتاح/قيمة عام تُحفظ فيه:
 * - لقطة المنصة المعتمة (`contacts_snapshot`): تمثيل JSON لـ [app.sila.feature.contacts.domain.ContactsSnapshot] —
 *   يُكتب **داخل نفس معاملة** كتابة دفعة الدلتا/الدفعة الأخيرة من الاستيراد حصراً،
 *   فلا يُقدَّم الرمز/اللقطة أبداً قبل التثبيت الفعلي للبيانات (لا تطبيق جزئي).
 * - ختم آخر مصالحة كاملة (`last_full_reconcile_at`): مللي ثانية منذ الإسك، لبوابة المصالحة الأسبوعية.
 *
 * الأنواع هنا معتمة عمداً (String) — فك/تغليف اللقطة مسؤولية طبقة الميزة
 * (`:feature:contacts:data`) وليس `:core:database`.
 */
@Entity(tableName = "contacts_sync_checkpoints")
data class ContactsSyncCheckpointEntity(
    @PrimaryKey val key: String,
    val value: String
) {
    companion object {
        /** مفتاح لقطة المنصة المعتمة (JSON). */
        const val KEY_SNAPSHOT = "contacts_snapshot"

        /** مفتاح ختم آخر مصالحة كاملة (مللي ثانية منذ الإسك كنص). */
        const val KEY_LAST_FULL_RECONCILE_AT = "last_full_reconcile_at"
    }
}
