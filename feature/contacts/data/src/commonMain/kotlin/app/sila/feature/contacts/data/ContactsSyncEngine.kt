package app.sila.feature.contacts.data

import app.sila.core.common.AppClock
import app.sila.core.database.dao.ContactsSyncDao
import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * محرك المزامنة التزايدية لجهات الاتصال (Issue #23 · [ADR-0010](docs/adr/0010-incremental-contacts-sync.md)).
 *
 * يعيش في `:feature:contacts:data` ويعتمد حصراً على [ContactsPlatformSource] و
 * [ContactsSyncDao] (Room = Single Source of Truth — ADR-0004) بلا شبكة ولا Firebase.
 *
 * ## السلوك
 * - **الاستيراد الأول** (لا لقطة محفوظة): `readDelta(null)` مرة واحدة ثم كتابة دفعات من
 *   [batchLimit] (~500) — أول دفعة ناجحة تجعل القائمة مقروءة فوراً؛ القبور + اللقطة في
 *   **معاملة الدفعة الأخيرة** حصراً، فانقطاع منتصف الاستيراد يترك بلا لقطة ⇒ استيراد
 *   idempotent يُستأنف من الصفر دون تكرار.
 * - **الدلتا**: قراءة من اللقطة المحفوظة ثم معاملة واحدة (upserts + قبور + لقطة).
 * - **المصالحة الدورية**: إذا حلّ `now - lastReconcileAt >= [reconcileIntervalMillis]`
 *   (بوابة أسبوعية كحدّ أدنى) تُنفَّذ قراءة كاملة تُصلح الانجراف.
 * - **إصلاح الانجراف**: في المصالحة الكاملة تحت [ContactsPermissionState.GRANTED] فقط،
 *   الصفوف الحيّة الغائبة عن مجموعة المنصة تُقبَّر (حذف ناعم). تحت `.limited` **لا حذف
 *   أبداً** (ADR-0010 §5) — نقص الظهور ليس حذفاً.
 * - **انتقالات الصلاحية**: أي انتقال إلى/بين {GRANTED, LIMITED} (منح بعد رفض بينهما)
 *   يجبر استيراداً كاملاً؛ DENIED/PERMANENTLY_DENIED توقف المزامنة (حالة
 *   [ContactsSyncState.PERMISSION_DENIED]) وتبقي Room مقروءة — ولا حذف أبداً.
 * - **الفساد**: لقطة غير قابلة للفك ⇒ [ContactsSyncState.FULL_RESYNC_REQUIRED] ثم استرجاع
 *   تلقائي باستيراد كامل (لا حذف أبداً).
 *
 * ## الحتمية والتزامن
 * - Mutex واحد يُسلسل كل دورات المزامنة (الدلتا idempotent فلا حاجة لتجميع الإشارات).
 * - بلا أي `delay()` — حتمية كاملة للاختبارات (تجميع الإشارات في المنصة المصدر ≥5 ثوانٍ).
 * - [start] idempotent (لا يرمي على البدء المزدوج — يستدعيه مهيئا المنصتين).
 * - [ContactsSyncState.FAILED] للاستثناءات الحقيقية فقط؛ الإلغاء ⇒ [ContactsSyncState.IDLE]
 *   ثم إعادة رمي (collectLatest يلغي المزامنة الجارية عند تغيّر الصلاحية، والمعاملة
 *   الذرّية في الـ DAO تضمن عدم تقديم أي شيء جزئي).
 */
class ContactsSyncEngine(
    private val platformSource: ContactsPlatformSource,
    private val syncDao: ContactsSyncDao,
    private val clock: AppClock,
    /** حجم الدفعة للاستيراد الكامل (~500 — ADR-0010 §2). */
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    /** الحد الأدنى الأسبوعي بين المصالحات الكاملة (ADR-0010 §3). */
    private val reconcileIntervalMillis: Long = DEFAULT_RECONCILE_INTERVAL_MILLIS,
) {

    private val _state = MutableStateFlow(ContactsSyncState.IDLE)

    /** حالة المزامنة المرئية للواجهة (Loading / Permission-denied / Error / Ready). */
    val state: StateFlow<ContactsSyncState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var lastPermission: ContactsPermissionState? = null

    private var permissionJob: Job? = null
    private var signalsJob: Job? = null

    /**
     * بدء مراقبة الصلاحيات وإشارات التغيير على النطاق المعطى (idempotent).
     * يُستدعى من مهيئات المنصات: `SilaApplication` بنطاق التطبيق و
     * `IosKoinBootstrap` بنطاق كائن محفوظ (كي لا يُهلك النطاق على Native).
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        permissionJob = scope.launch {
            platformSource.observePermissionState().collectLatest { permission ->
                val previous = lastPermission
                lastPermission = permission
                when (permission) {
                    ContactsPermissionState.GRANTED, ContactsPermissionState.LIMITED -> {
                        val forcedResync = previous != null && previous != permission
                        if (forcedResync) {
                            runGuarded { runFullResync(permission) }
                        } else {
                            runGuarded { runInitialOrDelta(permission) }
                        }
                    }

                    ContactsPermissionState.DENIED, ContactsPermissionState.PERMANENTLY_DENIED ->
                        // إيقاف المزامنة؛ collectLatest يلغي أي دورة جارية، وتبقى Room مقروءة.
                        _state.value = ContactsSyncState.PERMISSION_DENIED
                }
            }
        }
        signalsJob = scope.launch {
            platformSource.observeChangeSignals().collect { _: ContactsChangeSignal ->
                val permission = lastPermission
                if (permission == ContactsPermissionState.GRANTED ||
                    permission == ContactsPermissionState.LIMITED
                ) {
                    runGuarded { runInitialOrDelta(permission) }
                }
            }
        }
    }

    /** إيقاف مراقبة المحرك وإلغاء مهامه الخاصة فقط (بلا مساس بنطاق المستدعي). */
    fun stop() {
        started = false
        permissionJob?.cancel()
        signalsJob?.cancel()
        permissionJob = null
        signalsJob = null
        _state.value = ContactsSyncState.IDLE
    }

    /**
     * دورة مزامنة واحدة محمية بالـ Mutex — نقطة الدخول القابلة للاختبار المباشر
     * (المسار نفسه الذي تستدعيه مراقبتا الصلاحيات والإشارات).
     */
    internal suspend fun runSyncCycle(permission: ContactsPermissionState) {
        runGuarded { runInitialOrDelta(permission) }
    }

    private suspend fun runGuarded(block: suspend () -> Unit) {
        syncMutex.withLock {
            try {
                block()
            } catch (cancellation: CancellationException) {
                // الإلغاء ليس فشلاً — المعاملة الذرّية ضمنت عدم تقديم أي شيء جزئي.
                _state.value = ContactsSyncState.IDLE
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught")
                failure: Exception,
            ) {
                // فشل حقيقي: لا تقديم لقطة/رمز، والحالة FAILED — الحلقة تبقى حيّة لمحاولة لاحقة.
                _state.value = ContactsSyncState.FAILED
            }
        }
    }

    private suspend fun runInitialOrDelta(permission: ContactsPermissionState) {
        val snapshotJson = syncDao.loadSyncSnapshotJson()
        if (snapshotJson == null) {
            runFullResync(permission, initialImport = true)
            return
        }
        val snapshot = ContactsSnapshotCodec.decode(snapshotJson)
        if (snapshot == null) {
            // لقطة فاسدة/من إصدار غير معروف: إشارة الاسترجاع قبل المزامنة التلقائية الكاملة.
            _state.value = ContactsSyncState.FULL_RESYNC_REQUIRED
            runFullResync(permission, initialImport = false)
            return
        }
        if (isReconcileDue()) {
            runFullResync(permission, initialImport = false)
            return
        }
        runDelta(permission, snapshot)
    }

    private suspend fun runDelta(permission: ContactsPermissionState, snapshot: ContactsSnapshot) {
        _state.value = ContactsSyncState.INCREMENTAL_SYNC
        val delta = platformSource.readDelta(snapshot)
        val newSnapshot = requireDeltaCommitted(delta)
        val nowMillis = clock.nowEpochMillis()
        // قبور الدلتا بإثبات المنصة حصراً؛ تحت .limited لا حذف أبداً (ADR-0010 §5).
        val tombstones = if (permission == ContactsPermissionState.GRANTED) {
            delta.deletedSystemContactIds
        } else {
            emptyList()
        }
        syncDao.applySyncBatch(
            upserts = delta.upserted.map { it.toDomainContact(nowMillis) },
            tombstones = tombstones,
            snapshotJson = ContactsSnapshotCodec.encode(newSnapshot),
            reconcileStampMillis = null,
            nowMillis = nowMillis
        )
        _state.value = ContactsSyncState.READY
    }

    /**
     * استيراد/مصالحة كاملة: `readDelta(null)` ثم دفعات من [batchLimit] —
     * اللقطة + قبور إصلاح الانجراف (تحت GRANTED فقط) في معاملة الدفعة الأخيرة حصراً.
     */
    private suspend fun runFullResync(
        permission: ContactsPermissionState,
        initialImport: Boolean = false,
    ) {
        _state.value = if (initialImport) ContactsSyncState.INITIAL_IMPORT else ContactsSyncState.FULL_RESYNC_REQUIRED
        val delta = platformSource.readDelta(null)
        val newSnapshot = requireDeltaCommitted(delta)
        val nowMillis = clock.nowEpochMillis()
        val upserts = delta.upserted.map { it.toDomainContact(nowMillis) }
        val platformIds = delta.upserted.mapTo(mutableSetOf()) { it.systemContactId }
        val snapshotJson = ContactsSnapshotCodec.encode(newSnapshot)
        val batches = upserts.chunked(batchLimit)

        if (batches.isEmpty()) {
            // دفتر فارغ: تُقدَّم اللقطة والختم مع قبور الانجراف في معاملة واحدة.
            syncDao.applySyncBatch(
                upserts = emptyList(),
                tombstones = driftTombstones(platformIds, permission),
                snapshotJson = snapshotJson,
                reconcileStampMillis = clock.nowEpochMillis(),
                nowMillis = clock.nowEpochMillis()
            )
        } else {
            batches.forEachIndexed { index, batch ->
                val isFinalBatch = index == batches.lastIndex
                syncDao.applySyncBatch(
                    upserts = batch,
                    tombstones = if (isFinalBatch) driftTombstones(platformIds, permission) else emptyList(),
                    snapshotJson = if (isFinalBatch) snapshotJson else null,
                    reconcileStampMillis = if (isFinalBatch) clock.nowEpochMillis() else null,
                    nowMillis = clock.nowEpochMillis()
                )
            }
        }
        _state.value = ContactsSyncState.READY
    }

    /**
     * قبور إصلاح الانجراف: الصفوف الحيّة في Room الغائبة عن مجموعة المنصة الكاملة —
     * تحت GRANTED فقط؛ `.limited` لا تنتج حذفاً أبداً (ADR-0010 §5).
     */
    private suspend fun driftTombstones(
        platformIds: Set<String>,
        permission: ContactsPermissionState,
    ): List<String> {
        if (permission != ContactsPermissionState.GRANTED) return emptyList()
        return syncDao.getSyncIndexRows()
            .filter { row -> row.systemDeletedAt == null && row.systemContactId !in platformIds }
            .map { it.systemContactId }
    }

    /** عقد المنصة: أي فشل في القراءة يعيد `newSnapshot = null` — لا يُقدَّم شيء إطلاقاً (§8). */
    private fun requireDeltaCommitted(delta: ContactsDelta): ContactsSnapshot {
        val snapshot = delta.newSnapshot
        if (snapshot == null) {
            throw IllegalStateException(
                "ContactsPlatformSource returned a delta without a new snapshot; committing nothing (ADR-0010 §8)."
            )
        }
        return snapshot
    }

    private suspend fun isReconcileDue(): Boolean {
        val lastReconcileAt = syncDao.loadLastReconcileAtMillis() ?: return true
        return clock.nowEpochMillis() - lastReconcileAt >= reconcileIntervalMillis
    }

    companion object {
        /** حجم دفعة الاستيراد الكامل (~500 — ADR-0010 §2). */
        const val DEFAULT_BATCH_LIMIT = 500

        /** أسبوع واحد — الحد الأدنى بين المصالحات الكاملة (بوابة المصالحة، ADR-0010 §3). */
        const val DEFAULT_RECONCILE_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
