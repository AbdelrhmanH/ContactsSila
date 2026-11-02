package app.sila.core.database.dao

import app.sila.core.model.Contact

/**
 * واجهة DAO المزامنة التزايدية لجهات الاتصال (Issue #23 · ADR-0010 §1/§4/§7/§8).
 *
 * العقد الدلالي — يلتزم به التنفيذ (`LocalSilaDatabase`) بكل صفقة:
 * - [applySyncBatch] ذرّية بالكامل: كل الـ upsertات + القبور + اللقطة/الختم تُكتب في
 *   **معاملة SQLite واحدة**؛ أي فشل يُرجِع كل شيء (لا صفوف جزئية ولا تقديم لقطة).
 * - الـ upsert بمفتاح `systemContactId` حصراً (ADR-0009 §4): الصفوف الجديدة تُضاف،
 *   والموجودة تُحدَّث بأعمدة **المنصة المعكوسة فقط** مع الحفاظ الحرفي على أعمدة
 *   صِلَة (`category`, `isFavorite`, `customRingtoneUri`, `sendToVoicemail`, `isBlocked`,
 *   `excludeFromSmartFilter`) وصف `arham` — المزامنة لا تلمسها أبداً (ADR-0010 §7).
 *   (عمود `linkedServiceCloudId` مكتوب فقط في المخطط الحالي — قيد سابق خارج نطاق T2.)
 *   كذلك تُلغي صف `systemDeletedAt` (استعادة جهة حُذفت قبل أقل من 30 يوماً).
 * - [tombstones] تعليم حذف ناعم (`systemDeletedAt = nowMillis`) بلا أي حذف فعلي —
 *   الاسترجاع متاح 30 يوماً عبر [DataRetentionCleanupDao.cleanupExpiredData].
 * - إعادة تشغيل نفس الدفعة آمنة (idempotent): بلا تكرار وبلا آثار جانبية إضافية.
 */
interface ContactsSyncDao {

    /** لقطة المنصة المحفوظة (JSON معتم) أو `null` إن لم توجد (لم تتم أول مزامنة). */
    suspend fun loadSyncSnapshotJson(): String?

    /** ختم آخر مصالحة كاملة ناجحة (مللي ثانية منذ الإسك) أو `null`. */
    suspend fun loadLastReconcileAtMillis(): Long?

    /**
     * هويات الصفوف الحالية (`id` + `systemContactId` + `systemDeletedAt`) — مدخل
     * مصالحة الانجراف حصراً (مقارنة مجموعة المنصة الكاملة مع صفوف Room).
     */
    suspend fun getSyncIndexRows(): List<ContactSyncIndexRow>

    /**
     * تطبيق دفعة مزامنة ذرّية (معاملة واحدة).
     *
     * @param upserts جهات بحالتها النهائية الكاملة من المنصة (أعمدة صِلَة فيها مُهمَلة حصراً).
     * @param tombstones هويات `systemContactId` المحذوفة **بإثبات من المنصة** (ADR-0010 §3/§4)
     *   — تُعلَّم حذفاً ناعماً ولا تُحذف.
     * @param snapshotJson لقطة/رمز الاستئناف الجديد — يُكتب داخل نفس المعاملة حصراً؛
     *   `null` = لا تُقدَّم لقطة في هذه الدفعة (دفعة وسطى من الاستيراد).
     * @param reconcileStampMillis ختم المصالحة الكاملة إذا كانت هذه الدفعة تُتمّ مصالحة كاملة.
     * @param nowMillis ساعة المنطق الحالية (لأعمدة `updatedAt`/`systemDeletedAt`).
     */
    suspend fun applySyncBatch(
        upserts: List<Contact>,
        tombstones: List<String>,
        snapshotJson: String?,
        reconcileStampMillis: Long?,
        nowMillis: Long
    ): ContactsSyncBatchResult

    /** إسقاط اللقطة المحفوظة (استرجاع من فساد التخزين — تليها إعادة مزامنة كاملة). */
    suspend fun clearSyncSnapshot()
}

/** صف فهرس المزامنة لمقارنات المصالحة (بلا دلالات عرض). */
data class ContactSyncIndexRow(
    val id: Long,
    val systemContactId: String,
    val systemDeletedAt: Long?
)

/** نتيجة دفعة مزامنة (للتقارير والاختبارات). */
data class ContactsSyncBatchResult(
    val upserted: Int,
    val tombstoned: Int,
    /** صفوف استُعيدت (كانت مقبرة وأعادت المزامنة ظهورها). */
    val restored: Int
)
