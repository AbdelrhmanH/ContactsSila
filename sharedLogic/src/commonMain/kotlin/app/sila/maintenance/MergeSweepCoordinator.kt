package app.sila.maintenance

import app.sila.core.common.AppClock
import app.sila.feature.contacts.domain.ContactsMergeStore
import app.sila.feature.contacts.domain.MergeContactsUseCase
import app.sila.feature.contacts.domain.ContactsMergeWriter
import app.sila.feature.contacts.domain.PlatformSweepRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * منسّق المسح المنصوي المؤجل لدمج المكررات (T7 · Issue #28 · agy F3b — Position 2 من
 * النقاش): خلال جلسة الشاشة لا يُحذف أي raw منصوياً (التراجع = فصل ما اجُمع فقط + استرجاع
 * Room بلا هوية مفقودة)، وبعد انقضاء الجلسة بلا تراجع يُنفَّذ «حدّث واحداً واحذف الباقي»
 * (spec 04 §أ) هنا — **raw-مستوى حصراً** عبر [ContactsMergeWriter.sweepAbsorbedRawContacts]
 * (حذف `CONTENT_LOOKUP_URI` الكلي يقتل raws حسابات أخرى في المجمّعة — خطر Lane D).
 *
 * الالتزامات (نمط [RetentionCleanupCoordinator]):
 * - single-flight لكل عملية؛ إطلاق على نطاق عمر التطبيق بلا حجب رئيسي.
 * - حتمي وقابل للاستئناف: اللقطات المرشحة = ACTIVE انقضت `createdAt` عن [SESSION_SWEEP_BOUND_MILLIS]
 *   (حد مطلق) — الانقطاع يُستكمل عند الإقلاع التالي.
 * - الحارس: الحذف بمعرفات raw المسجَّلة في اللقطة ذاتها — لا استنتاج من الحالة الحية.
 * - فشل المنصة = اللقطة تبقى ACTIVE (تُعاد محاولتها عند الإقلاع التالي) — لا كتم بلا سجل
 *   (سجل بلا PII)، ولا ختم EXPIRED إلا بعد نجاح المسح.
 * - تنظيف صفوف اللقطات المنقضية (RESTORED/EXPIRED) يركب نفس الدورة (agy F1).
 */
class MergeSweepCoordinator(
    private val mergeStore: ContactsMergeStore,
    private val mergeWriter: ContactsMergeWriter,
    private val clock: AppClock,
) {

    private val runMutex = Mutex()

    @Volatile
    private var started = false

    class SweepReport(val sweptSnapshots: Int, val expiredSnapshots: Int, val purgedSnapshots: Boolean)

    /** يُطلق المسح على نطاق عمر التطبيق (نمط RetentionCleanupCoordinator — single-flight داخلي). */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { runOnce() }

    suspend fun runOnce(): SweepReport? = runMutex.withLock {
        if (started) return null
        started = true
        try {
            val now = clock.nowEpochMillis()
            val candidates = mergeStore.activeSnapshotsForSweep(olderThan = now - SESSION_SWEEP_BOUND_MILLIS)
            var swept = 0
            var expired = 0
            for (snapshot in candidates) {
                val rawIds = snapshot.content.absorbedRawIds.filterKeys { it in snapshot.content.absorbedSystemIds.toSet() }
                if (rawIds.isEmpty()) {
                    // بلا هويات raw مسجَّلة (iOS — حُذفت في الدمج؛ أو لقطة قديمة): ختم فقط.
                    mergeStore.markSnapshotExpired(snapshot.snapshotId)
                    expired++
                    continue
                }
                when (mergeWriter.sweepAbsorbedRawContacts(PlatformSweepRequest(absorbedRawIds = rawIds))) {
                    is app.sila.core.common.AppResult.Success -> {
                        mergeStore.markSnapshotExpired(snapshot.snapshotId)
                        swept++
                        expired++
                    }
                    is app.sila.core.common.AppResult.Error -> {
                        println("[MergeSweep] platform sweep deferred for snapshot ${snapshot.snapshotId} (no PII)")
                    }
                }
            }
            mergeStore.purgeSnapshotsOlderThan(olderThan = now - SNAPSHOT_RETENTION_MILLIS)
            SweepReport(sweptSnapshots = swept, expiredSnapshots = expired, purgedSnapshots = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("[MergeSweep] merge sweep failed: ${failure::class.simpleName}: ${failure.message ?: "no message"}")
            null
        }
    }

    companion object {
        /** انقضاء جلسة التراجع — حد المسح (نفس حدود أمان اللقطة في حالة الاستخدام). */
        const val SESSION_SWEEP_BOUND_MILLIS: Long = MergeContactsUseCase.SESSION_SNAPSHOT_EXPIRY_MILLIS

        /** احتفاظ صفوف اللقطات المنقضية قبل الحذف (يوازي القبور 30 يوماً). */
        const val SNAPSHOT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
