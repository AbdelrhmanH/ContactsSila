package app.sila.feature.contacts.data

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.ContactsSyncBatchResult
import app.sila.core.database.dao.ContactsSyncDao
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * عقد سلامة بوابة المصالحة الكاملة (FINDING-SYNC-1 · ADR-0022):
 *
 * **بوابة المصالحة (`last_full_reconcile_at`) لا تتقدم أبداً إلا بعد اكتمال استيراد
 * يطابق كتالوج اللقطة** — العطل المرصود على المحاكي (جلسة `phase1-remaining-work`):
 * لقطة كاملة (5000 سجل خام بمفاتيحها) + ختم مصالحة بينما `contacts = 0` في Room
 * والواجهة `All 0` لأكثر من 13 دقيقة.
 *
 * كل شيء حتمي: بلا `delay()`، وموزعات Unconfined، وقاعدة `:memory:` حقيقية.
 */
class ContactsSyncCheckpointIntegrityTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private class FakeClock(var nowMillis: Long = 1_800_000_000_000L) : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(nowMillis)
    }

    /** غلاف DAO ببوابة إيقاف عند النداء رقم [pauseAtCall] — لمحاكاة المقاطعة منتصف الاستيراد. */
    private class GatedSyncDao(private val delegate: ContactsSyncDao) : ContactsSyncDao by delegate {
        var pauseAtCall = Int.MAX_VALUE
        val releaseGate = CompletableDeferred<Unit>()
        var enteredCount = 0
            private set

        override suspend fun applySyncBatch(
            upserts: List<app.sila.core.model.Contact>,
            tombstones: List<String>,
            snapshotJson: String?,
            reconcileStampMillis: Long?,
            nowMillis: Long,
        ): ContactsSyncBatchResult {
            enteredCount++
            if (enteredCount == pauseAtCall) releaseGate.await()
            return delegate.applySyncBatch(upserts, tombstones, snapshotJson, reconcileStampMillis, nowMillis)
        }
    }

    /** كتالوج أندرويد غير فارغ (نمط العطل الأصلي: سجلات خام بنسخها). */
    private fun androidCatalog(vararg rawIds: String): ContactsSnapshot = ContactsSnapshot(
        platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
        androidRawContacts = rawIds.associate { id ->
            id to app.sila.feature.contacts.domain.AndroidRawContactState(version = 1L)
        }
    )

    private fun newEngine(
        db: LocalSilaDatabase,
        source: FakeContactsPlatformSource,
        clock: FakeClock,
        syncDao: ContactsSyncDao = db,
        batchLimit: Int = ContactsSyncEngine.DEFAULT_BATCH_LIMIT,
    ): ContactsSyncEngine = ContactsSyncEngine(
        platformSource = source,
        syncDao = syncDao,
        clock = clock,
        batchLimit = batchLimit
    )

    /**
     * **اختبار التراجع الأساسي (FINDING-SYNC-1):** مصالحة كاملة تعيد كتالوجاً غير فارغ
     * بصفر جهات مجسَّدة (نمط العطل المرصود: `RawContacts` ممتلئ وقراءة `Contacts`
     * التجميعية فارغة) — يجب أن يرفض المحرك تقديم اللقطة والختم ويعلن فشلاً صادقاً.
     */
    @Test
    fun fullResyncCatalogWithoutImportRefusesCheckpointAndStaysFailed() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource() // بلا صفحات: upserts فارغة
            source.snapshotForDelta = androidCatalog("r1", "r2", "r3")
            val engine = newEngine(db, source, FakeClock())

            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            assertEquals(ContactsSyncState.FAILED, engine.state.value)
            assertNull(db.loadLastReconcileAtMillis(), "بوابة المصالحة يجب ألا تتقدم بلا استيراد")
            assertNull(db.loadSyncSnapshotJson(), "اللقطة يجب ألا تُقدَّم بلا استيراد مطابق")
            assertTrue(db.observeContacts().first().isEmpty())
        } finally {
            db.closeConnection()
        }
    }

    /** الدفتر الفارغ شرعي: كتالوج فارغ + صفر upserts ← الختم واللقطة يتقدمان طبيعياً. */
    @Test
    fun fullResyncEmptyBookCommitsStampAndSnapshot() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource() // بلا صفحات: دفتر فارغ فعلاً
            source.snapshotForDelta = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_ANDROID)
            val engine = newEngine(db, source, FakeClock())

            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertNotNull(db.loadLastReconcileAtMillis())
            assertNotNull(db.loadSyncSnapshotJson())
            assertTrue(db.observeContacts().first().isEmpty())
        } finally {
            db.closeConnection()
        }
    }

    /** إعادة المحاولة بعد قراءة ناقصة (ADR-0010 §8): الدورة التالية تُكمل وتختم فقط عند النجاح. */
    @Test
    fun fullResyncRetryAfterIncompleteReadCompletesAndStamps() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val incomplete = ContactsDelta(
                upserted = emptyList(),
                deletedSystemContactIds = emptyList(),
                newSnapshot = null, // عقد القراءة الناقصة: بلا لقطة — لا يُقدَّم شيء
                isFullResync = true
            )
            val complete = ContactsDelta(
                upserted = listOf(
                    FakeContactsPlatformSource.contact("p1", lookupKey = "lookup-1"),
                    FakeContactsPlatformSource.contact("p2", lookupKey = "lookup-2")
                ),
                deletedSystemContactIds = emptyList(),
                newSnapshot = androidCatalog("r1", "r2"),
                isFullResync = true
            )
            val source = FakeContactsPlatformSource().withDeltas(incomplete, complete)
            val engine = newEngine(db, source, FakeClock())

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // فاشلة: قراءة ناقصة
            assertEquals(ContactsSyncState.FAILED, engine.state.value)
            assertNull(db.loadLastReconcileAtMillis())

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // إعادة المحاولة: تكتمل

            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(2, db.observeContacts().first().size)
            assertNotNull(db.loadLastReconcileAtMillis())
            assertNotNull(db.loadSyncSnapshotJson())
        } finally {
            db.closeConnection()
        }
    }

    /** المقاطعة منتصف الاستيراد: بلا لقطة/ختم، والدفعات المنجزة تبقى، والإعادة تكتمل دون تكرار. */
    @Test
    fun fullResyncCancellationMidImportLeavesNoCheckpointThenRetryCompletes() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val gated = GatedSyncDao(db)
            gated.pauseAtCall = 2 // أوقف الدفعة الثانية (الأخيرة التي تحمل اللقطة والختم)
            val source = FakeContactsPlatformSource().withPages(
                listOf(
                    FakeContactsPlatformSource.contact("c1", lookupKey = "lookup-1"),
                    FakeContactsPlatformSource.contact("c2", lookupKey = "lookup-2")
                ),
                listOf(FakeContactsPlatformSource.contact("c3", lookupKey = "lookup-3"))
            )
            source.snapshotForDelta = androidCatalog("r1", "r2", "r3")
            val engine = newEngine(db, source, FakeClock(), syncDao = gated, batchLimit = 2)

            val cycle = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            yield() // الدورة جارت حتى تعليقها عند بوابة الدفعة الثانية (الدفعة الأولى مُقدَّمة)
            cycle.cancelAndJoin()

            assertEquals(ContactsSyncState.IDLE, engine.state.value, "الإلغاء ليس فشلاً (عقد المحرك)")
            assertNull(db.loadLastReconcileAtMillis(), "المقاطعة تترك البوابة بلا تقدم")
            assertNull(db.loadSyncSnapshotJson(), "المقاطعة تترك بلا لقطة (الدفعة الأخيرة فقط تحملها)")
            assertEquals(
                setOf("c1", "c2"),
                db.observeContacts().first().map { it.systemContactId }.toSet()
            )

            // إعادة المزامنة idempotent: تكتمل دون تكرار وتختم عند النجاح حصراً
            // (عدّاد `enteredCount` تراكمي فيتخطى بوابة `pauseAtCall = 2` تلقائياً).
            engine.runSyncCycle(ContactsPermissionState.GRANTED)
            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(
                setOf("c1", "c2", "c3"),
                db.observeContacts().first().map { it.systemContactId }.toSet()
            )
            assertNotNull(db.loadLastReconcileAtMillis())
            assertNotNull(db.loadSyncSnapshotJson())
        } finally {
            db.closeConnection()
        }
    }

    /** دورات الدلتا لا تلمس بوابة المصالحة إطلاقاً (البوابة للمصالحة الكاملة حصراً). */
    @Test
    fun deltaCyclesNeverAdvanceReconcileStamp() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(
                listOf(FakeContactsPlatformSource.contact("p1", lookupKey = "lookup-1"))
            )
            source.snapshotForDelta = androidCatalog("r1")
            val clock = FakeClock()
            val engine = newEngine(db, source, clock)

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // استيراد كامل يختم
            val stampedAt = assertNotNull(db.loadLastReconcileAtMillis())

            clock.nowMillis += 60_000L // مرور وقت لا يهم: الدلتا ليست مصالحة
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // دلتا (فارغة عن المنصة)

            assertEquals(stampedAt, db.loadLastReconcileAtMillis())
            assertEquals(ContactsSyncState.READY, engine.state.value)
        } finally {
            db.closeConnection()
        }
    }
}
