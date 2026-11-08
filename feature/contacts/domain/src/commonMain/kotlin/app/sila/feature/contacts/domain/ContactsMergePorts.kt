package app.sila.feature.contacts.domain

import app.sila.core.common.AppResult
import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * محتوى لقطة الدمج — النموذج النطاقي الكامل لما-قبل-الدمج (T7 · Issue #28). تبنيه طبقة
 * الميزة وتُكتفى JSON معتماً داخل معاملة الدمج (عقد `contacts_sync_checkpoints`)، وتُفكّ
 * داخل معاملة الاسترجاع. الصفوف بأصفارها الأصلية (id والهويات المنصوية) كي يكتبها
 * الاسترجاع بمعرّفاتها حرفاً (agy F2c/F3a).
 */
data class MergeSnapshotContent(
    val members: List<Contact>,
    val absorbedSystemIds: List<String>,
    val callEventRepoints: List<CallEventRepoint>,
    val parentLinkRepoints: List<ParentLinkRepoint>,
    val absorbedVCards: Map<String, String>,
    /** هويات raw المنصة المعتمة لكل ممتص (أندرويد — agy F3b)؛ فارغة على iOS. */
    val absorbedRawIds: Map<String, List<String>> = emptyMap(),
) {
    data class CallEventRepoint(val callLogId: String, val oldContactId: Long)
    data class ParentLinkRepoint(val childContactId: Long, val oldParentId: Long)
}

/** المراجع المكتشفة داخل معاملة الدمج قبل أي كتابة — مدخل باني المحتوى. */
data class DiscoveredReferences(
    val callLogIds: List<String>,
    val parentLinks: List<Pair<Long, Long>>,
)

/** ناتج معاملة الدمج في Room — لقطة الsession ومفاتيح التراجع. */
data class MergeRoomCommit(
    val snapshotId: Long,
    val groupId: String,
    val callEventRepoints: List<MergeSnapshotContent.CallEventRepoint>,
    val parentLinkRepoints: List<MergeSnapshotContent.ParentLinkRepoint>,
)

/** ناتج الاسترجاع داخل Room — القفل لمرة-واحدة (agy F2c: الهويات المُعاد بناؤها معادَة بالتعيين). */
sealed interface UndoRoomOutcome {
    data class Restored(val content: MergeSnapshotContent) : UndoRoomOutcome

    /** اللقطة ليست ACTIVE (استُرجعت سلفاً أو انقضت) — رفض بلا أي كتابة. */
    data object SnapshotNotActive : UndoRoomOutcome
}

/** لقطة نشطة قابلة للمسح المؤجل (منسّق `:sharedLogic` — agy F3b). */
data class SweepableSnapshot(
    val snapshotId: Long,
    val groupId: String,
    val content: MergeSnapshotContent,
    val createdAt: Long,
)

/** وزن تعلّم محلي (token→category) — نوع نطاقي نقي بلا كيانات قاعدة. */
data class SmartFilterWeight(
    val token: String,
    val category: Category,
    val acceptedCount: Long,
    val rejectedCount: Long,
) {
    /** صفر-قبول/صفر-رفض محايد؛ الرفض المسيطر يُسقط الاقتراح من التحديد المسبق. */
    val isRejectedDominant: Boolean get() = rejectedCount > acceptedCount
}

/**
 * منفذ تخزين الدمج (Room) — تُنفذه `:feature:contacts:data` فوق واجهة `:core:database`
 * الخارجية. النطاق لا يرى SQL ولا كيانات قاعدة (ADR-0007 · Rule 3).
 */
interface ContactsMergeStore {

    /**
     * معاملة الدمج: اكتشاف المراجع + بناء المحتوى ([buildContent] داخل المعاملة) + اللقطة
     * + الناجي + إعادة التوجيه + القبور — كلها أو لا شيء. الكاش يُحدَّث بعد COMMIT حصراً.
     */
    suspend fun mergeInRoom(
        survivorId: Long,
        mergedContact: Contact,
        absorbedIds: List<Long>,
        groupId: String,
        expiresAt: Long,
        nowMillis: Long,
        buildContent: (DiscoveredReferences) -> MergeSnapshotContent,
    ): MergeRoomCommit

    /**
     * الاسترجاع القفلِي: ACTIVE فقط؛ [remap] يُطبَّق على المحتوى المفكوك داخل المعاملة
     * (خريطة الهويات الجديدة بعد إعادة الإنشاء المنصوي — iOS agy F3a) ثم يُكتب كل عضو
     * بمعرّفه الأصلي. النداء الثاني على نفس اللقطة يُرجع [UndoRoomOutcome.SnapshotNotActive].
     */
    suspend fun undoInRoom(
        snapshotId: Long,
        nowMillis: Long,
        remap: (MergeSnapshotContent) -> MergeSnapshotContent,
    ): UndoRoomOutcome

    /** محتوى لقطة نشطة بالمعرّف — للعرض والتراجع (bلا قبور). */
    suspend fun snapshotContent(snapshotId: Long): MergeSnapshotContent?

    // ——— المسح المؤجل بعد انقضاء الجلسة (agy F3b — من `:sharedLogic`) ———

    suspend fun activeSnapshotsForSweep(olderThan: Long): List<SweepableSnapshot>
    suspend fun markSnapshotExpired(snapshotId: Long)
    suspend fun purgeSnapshotsOlderThan(olderThan: Long)

    // ——— استبعاد الأزواج والتعلّم المحلي ———

    fun observeDismissedPairKeys(): Flow<Set<String>>
    suspend fun dismissPair(groupKey: String, reason: String, nowMillis: Long)
    fun observeFeedback(): Flow<List<SmartFilterWeight>>
    suspend fun recordFeedback(token: String, category: Category, accepted: Boolean)
}

/** طلب الدمج المنصوي — جهات النطاق؛ التنفيذ يحوّلها إسقاطاً ضيّقاً بذاته (ADR-0012 §2). */
data class PlatformMergeRequest(val survivor: Contact, val absorbed: List<Contact>)

/**
 * ناتج الدمج المنصوي: **هوية الناجي بعد التجميع** — المزود قد يجعل المفتاح الموحّد مفتاح
 * الممتص (agy P3a) فيجب أن يعيد الكاتب المفتاح المُعاد حلّه ليُهاجر صف Room الناجي إليه
 * داخل معاملة الدمج نفسها. [absorbedRawIds] هويات raw المنصة المعتمة لكل ممتص
 * (أندرويد) — تُسجَّل في اللقطة ليتحقق منها المسح المؤجل بعد انقضاء الجلسة (agy F3b:
 * «snapshot-recorded raw ids»)؛ على iOS فارغة (الحذف تم في الدمج).
 */
data class PlatformMergeResult(
    val unifiedSystemContactId: String,
    val unifiedLookupKey: String?,
    val absorbedRawIds: Map<String, List<String>> = emptyMap(),
)

/** طلب فك الدمج المنصوي — بطاقات vCard للإعادة الإنشاء على iOS (agy F3a). */
data class PlatformUnmergeRequest(
    val survivor: Contact,
    val absorbed: List<Contact>,
    val absorbedVCards: Map<String, String>,
)

/**
 * ناتج فك الدمج: خريطة هويات الممتصين (القديم → الجديد) — أندرويد `KEEP_SEPARATE` تحفظ
 * الهويات (خريطة مطابقة)، وإعادة الإنشاء على iOS تولّد معرفات جديدة يجب أن تُهاجر صفوف
 * Room المسترجعة إليها داخل معاملة الاسترجاع.
 */
data class PlatformUnmergeResult(val absorbedSystemIds: Map<String, String>)

/** طلب المسح المؤجل: raws الممتصين المسجَّلة في اللقطة (agy F3b) — حذف raw-مستوى حصراً. */
data class PlatformSweepRequest(val absorbedRawIds: Map<String, List<String>>)

/**
 * منفذ الدمج المنصوي — عقد ضيق مستقل عن كاتب حقول T6 المجمد (خيار A من Lane D):
 * الدمج عملية على مستوى رسم RawContacts/هوية، لا كتابة حقول. التنفيذات منصوية في `data`
 * مع ربط Koin بجذر التركيب (نمط ADR-0009 §2) — ADR-0013 يوثق العقد.
 */
interface ContactsMergeWriter {
    suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult>
    suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult>

    /**
     * المسح المؤجل بعد انقضاء جلسة التراجع بلا تراجع (agy F3b): حذف raws الممتصين
     * المسجَّلة في اللقطة — **raw-مستوى حصراً** (حذف LOOKUP_URI الكلي يقتل raws حسابات
     * أخرى في المجمّعة). على iOS لا شيء يُحذف (تم في الدمج) — نجاح فوري.
     */
    suspend fun sweepAbsorbedRawContacts(request: PlatformSweepRequest): AppResult<Unit>
}
