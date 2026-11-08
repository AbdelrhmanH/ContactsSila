package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import kotlinx.coroutines.CancellationException

/**
 * التراجع عن دمج خلال جلسة الشاشة (T7 · Issue #28 — spec 04 §أ: «التراجع خلال جلسة
 * الشاشة يعيد الوضع كما كان — لقطة محفوظة في Room») — عهد J-1 بترتيبه:
 *
 * 1. **المنصة أولاً** (idempotent): أندرويد `KEEP_SEPARATE` لكل ثنائية مسجَّلة — القبر
 *    لم يُكتب منصوياً أبداً خلال الجلسة فالاسترجاع يفصل ما اجُمع فقط؛ iOS يعيد إنشاء
 *    الممتصين من بطاقات vCard داخل اللقطة (agy F3a) ويعيد معرفاتهم الجديدة.
 * 2. **معاملة Room واحدة**: القفل ACTIVE→RESTORED + إحياء الممتصين وإعادة كتابة كل عضو
 *    بأصفاره (مع خريطة الهويات الجديدة على iOS) + إعادة توجيه الأحداث والروابط بالعكس.
 *    النداء الثاني: [UndoRoomOutcome.SnapshotNotActive] بلا أي كتابة.
 *
 * بعد التراجع قد تظهر المجموعة نفسها مجدداً كاقتراح (سلوك ADR-0009 §4 الموثق — الدليل
 * سليم والمستخدم قرر)؛ والانقضاء بلا تراجع يتبعه المسح المنصوي المؤجل (agy F3b).
 */
class UndoMergeUseCase(
    private val mergeWriter: ContactsMergeWriter,
    private val mergeStore: ContactsMergeStore,
    private val clock: AppClock,
) {

    suspend operator fun invoke(snapshotId: Long): AppResult<Unit> {
        val content = mergeStore.snapshotContent(snapshotId)
            ?: return AppResult.Error(DataError.NotFound(entityId = "merge_snapshot_$snapshotId"))
        val survivor = content.members.minWith(compareBy({ it.createdAt }, { it.id }))
        val absorbed = content.members.filter { it.systemContactId in content.absorbedSystemIds.toSet() }

        // J-1: المنصة أولاً — idempotent؛ فشلها يمنع أي مساس Room.
        val platformResult = when (val result = mergeWriter.unmergeContacts(
            PlatformUnmergeRequest(
                survivor = survivor,
                absorbed = absorbed,
                absorbedVCards = content.absorbedVCards,
            ),
        )) {
            is AppResult.Error -> return result
            is AppResult.Success -> result.data
        }

        return try {
            when (val outcome = mergeStore.undoInRoom(snapshotId, clock.nowEpochMillis()) { original ->
                if (platformResult.absorbedSystemIds.isEmpty()) {
                    original
                } else {
                    original.copy(
                        members = original.members.map { member ->
                            platformResult.absorbedSystemIds[member.systemContactId]
                                ?.let { newId -> member.copy(systemContactId = newId) }
                                ?: member
                        },
                    )
                }
            }) {
                is UndoRoomOutcome.Restored -> AppResult.Success(Unit)
                UndoRoomOutcome.SnapshotNotActive ->
                    AppResult.Error(DataError.Unknown(causeMessage = "merge snapshot already consumed"))
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // المنصة فُكَّت والRoom فشل: القفل لم يُلتف، الممتصون أحياء على المنصة —
            // المزامنة تعيد المحاذاة نحو المنصة (اتجاه ADR-0012 الصحيح)؛ خطأ صادق.
            AppResult.Error(DataError.Unknown(causeMessage = t.message))
        }
    }
}
