package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * لقطة ما-قبل-الدمج الكاملة لجهات الاتصال المدموجة (T7 — Issue #28 · ADR-0004: اللقطة في Room
 * حصراً). الحمولة JSON معتمة يفكّها `:feature:contacts:data` حصراً (عقد
 * `contacts_sync_checkpoints` نفسه): كل صف contacts + أبناؤه + صفوف أرحام + قائمة إعادة
 * توجيه call_events + خريطة هويات المنصة الخام + **حمولة vCard للمرآة المنصوية** (agy F3a —
 * بلا ملفات .vcf غير مشفّرة على القرص إطلاقاً).
 *
 * حالة الاسترجاع [status] قفلُ لمرة-واحدة: ACTIVE → RESTORED (استرجاع ناجح) أو EXPIRED
 * (انقضت جلسة الشاشة بلا تراجع — يُتبعها مسح منصوي مؤجَّل من منسّق `:sharedLogic` ثم
 * تنظيف الصفوف). الاسترجاع الثاني يُرفض فاشلاً صادقاً (idempotency بالقفل).
 */
@Entity(
    tableName = "contact_merge_snapshots",
    indices = [
        Index(name = "idx_contact_merge_snapshots_groupId", value = ["groupId"]),
        Index(name = "idx_contact_merge_snapshots_createdAt", value = ["createdAt"]),
    ],
)
data class ContactMergeSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val groupId: String,
    val payloadJson: String,
    val contactCount: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'ACTIVE'") val status: String = STATUS_ACTIVE,
    val restoredAt: Long? = null,
    val expiresAt: Long,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RESTORED = "RESTORED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
