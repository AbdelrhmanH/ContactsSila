package app.sila.feature.contacts.data

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات ملكية دورة حياة [ContactsSyncEngine] (إصلاح دورة حياة الموارد المشتركة
 * T8+DEF-01 — النطاق B): المهمة الجامعة [ContactsSyncEngine.engineLifecycleJobForTest]
 * مملوكة للمحرك وابنة بنيوية لنطاق المستدعي، و`stop()` تُلغيها و**تنتظر** اكتمال
 * تفكيك كل أبنائها — كوروتين المشاركة (وبعده مصدر الصلاحية حتى `awaitClose`) والجامعَين —
 * قبل عودتها، فلا عملٌ للمحرك ينجو من الإيقاف ولا من إلغاء النطاق الأب.
 * كل شيء حتمي: بوابات `CompletableDeferred` وموزعات Unconfined وقاعدة `:memory:`
 * حقيقية — بنفس أحداد اختبارات المحرك القائمة، وبلا `delay()` ولا `Thread.sleep`.
 */
class ContactsSyncEngineLifecycleOwnershipTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private class FakeClock(var nowMillis: Long = 1_800_000_000_000L) : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(nowMillis)
    }

    private fun androidSnapshot(vararg rawIds: Pair<String, Long>): ContactsSnapshot =
        ContactsSnapshot(
            platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
            androidRawContacts = rawIds.associate { (id, version) ->
                id to AndroidRawContactState(version = version)
            }
        )

    private fun grantedSourceWithOneContact(): FakeContactsPlatformSource =
        FakeContactsPlatformSource(initialPermission = ContactsPermissionState.GRANTED)
            .withPages(listOf(FakeContactsPlatformSource.contact("c1")))
            .apply { snapshotForDelta = androidSnapshot("r1" to 1L) }

    /**
     * مصدر يعدّ نداءات بناء مراقب الصلاحية — بصمة «استطلاع واحد» (النطاق B1):
     * كل استدعاء [ContactsSyncEngine.start] يجب أن يبني التدفق مرة واحدة حصراً.
     */
    private class CountingPermissionSource(
        private val delegate: FakeContactsPlatformSource,
    ) : app.sila.feature.contacts.domain.ContactsPlatformSource by delegate {
        var permissionFlowConstructions = 0
            private set
        override fun observePermissionState(): Flow<ContactsPermissionState> {
            permissionFlowConstructions++
            return delegate.observePermissionState()
        }
    }

    /**
     * مصدر يغلّف مراقب الصلاحية في `callbackFlow` تُكمل بوابته عند التفكيك —
     * البرهان الحتمي على أن `stop()` المنتظرة انتظرت خروج المصدر (awaitClose)
     * فعلاً لا مجرد طلب الإلغاء.
     */
    private class UpstreamCloseProbeSource(
        private val delegate: FakeContactsPlatformSource,
    ) : app.sila.feature.contacts.domain.ContactsPlatformSource by delegate {
        val upstreamClosed = CompletableDeferred<Unit>()
        override fun observePermissionState(): Flow<ContactsPermissionState> = callbackFlow {
            val bridge = launch { delegate.permissionState.collect { trySend(it) } }
            awaitClose {
                bridge.cancel()
                upstreamClosed.complete(Unit)
            }
        }
    }

    /** حلقة استطلاع لا نهائية معلّقة على بوابة — تفكيكها عند الإلغاء يُسجَّل بعلم. */
    private class GatedPollingSource(
        private val delegate: FakeContactsPlatformSource,
    ) : app.sila.feature.contacts.domain.ContactsPlatformSource by delegate {
        val releaseGate = CompletableDeferred<Unit>()
        var pollerReleased = false
            private set
        override fun observePermissionState(): Flow<ContactsPermissionState> = flow {
            try {
                while (true) {
                    emit(ContactsPermissionState.GRANTED)
                    releaseGate.await()
                }
            } finally {
                pollerReleased = true
            }
        }
    }

    private fun newEngine(
        db: LocalSilaDatabase,
        source: app.sila.feature.contacts.domain.ContactsPlatformSource,
    ): ContactsSyncEngine = ContactsSyncEngine(
        platformSource = source,
        syncDao = db,
        clock = FakeClock()
    )

    @Test
    fun engine_start_creates_one_ownedSharingLifecycle() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = CountingPermissionSource(grantedSourceWithOneContact())
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)

            val lifecycle = engine.engineLifecycleJobForTest
            assertTrue(lifecycle?.isActive == true, "start must create one active engine-owned lifecycle job")
            assertEquals(
                1,
                source.permissionFlowConstructions,
                "exactly one permission upstream must be built per start (no duplicate poll construction)"
            )

            engine.start(engineScope) // idempotent — البدء المزدوج لا يبني دورة حياة ثانية
            assertEquals(
                1,
                source.permissionFlowConstructions,
                "repeated start must not rebuild the permission upstream"
            )
            assertTrue(
                engine.engineLifecycleJobForTest === lifecycle,
                "repeated start must keep the same lifecycle job"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_stop_cancelsAndJoinsSharingLifecycle() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = UpstreamCloseProbeSource(grantedSourceWithOneContact())
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertTrue(
                source.upstreamClosed.isActive,
                "upstream must be live while the lifecycle is running"
            )

            engine.stop() // تعليق: يعود بعد اكتمال تفكيك كوروتين المشاركة ومصدره

            assertTrue(
                source.upstreamClosed.isCompleted,
                "suspend stop() must await the sharing coroutine's upstream teardown (awaitClose completed)"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun engine_stop_cancelsAllCollectors() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(1, source.changeSignalSubscriptionCount.value, "signals collected while granted")

            engine.stop()

            assertEquals(
                0,
                source.changeSignalSubscriptionCount.value,
                "stop must leave zero active signals collectors"
            )
            assertEquals(ContactsSyncState.IDLE, engine.state.value, "state reset to IDLE after stop")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun engine_stop_releasesPermissionPolling() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val fake = grantedSourceWithOneContact()
        val source = GatedPollingSource(fake)
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(
                ContactsSyncState.READY,
                engine.state.first { it == ContactsSyncState.READY },
                "the gated poll loop delivers GRANTED and the initial import completes"
            )

            engine.stop() // يلغي الجامع المنتظر على البوابة — حلقة الاستطلاع تفكك فعلياً

            assertTrue(
                source.pollerReleased,
                "cancelling the lifecycle must terminate the (gated) permission poll loop"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun engine_parentCancellation_stopsChildLifecycle() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(1, source.changeSignalSubscriptionCount.value)

            engineScope.cancel() // إلغاء النطاق الأب — بلا استدعاء stop()

            runCurrent()
            assertEquals(
                0,
                source.changeSignalSubscriptionCount.value,
                "parent-scope cancellation must propagate to the engine-owned lifecycle and stop all collectors"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun engine_repeatedStartStop_doesNotDuplicateCollectors() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val firstLifecycle = run {
                engine.start(engineScope)
                engine.engineLifecycleJobForTest
            }
            assertEquals(1, source.changeSignalSubscriptionCount.value)

            engine.stop()
            assertEquals(0, source.changeSignalSubscriptionCount.value)

            engine.start(engineScope)
            val secondLifecycle = engine.engineLifecycleJobForTest
            assertEquals(1, source.changeSignalSubscriptionCount.value, "restart subscribes exactly once")
            assertNotEquals(firstLifecycle, secondLifecycle, "each start builds a fresh lifecycle job")

            engine.stop()
            assertEquals(0, source.changeSignalSubscriptionCount.value)

            engine.start(engineScope)
            assertEquals(1, source.changeSignalSubscriptionCount.value)
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_restart_createsFreshLifecycle_ifSupportedByContract() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(
                ContactsSyncState.READY,
                engine.state.first { it == ContactsSyncState.READY }
            )
            assertEquals(1, source.readDeltaRequestCount, "initial import runs once")

            engine.stop()
            engine.start(engineScope) // الإعادة مدعومة بالتعاقد: دورة حياة طازجة

            assertEquals(
                ContactsSyncState.READY,
                engine.state.first { it == ContactsSyncState.READY },
                "restart reaches READY without a process restart"
            )
            assertEquals(1, source.changeSignalSubscriptionCount.value)
            assertEquals(2, source.readDeltaRequestCount, "restart runs exactly one more sync cycle")
            assertTrue(
                source.readDeltaSnapshots.lastOrNull() != null,
                "restart with unchanged GRANTED runs a delta from the preserved snapshot (no forced resync)"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_stop_waitsForObserverUnregistration() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(1, source.changeSignalSubscriptionCount.value, "observer active while granted")

            engine.stop()

            assertEquals(
                0,
                source.changeSignalSubscriptionCount.value,
                "stop() returns only after the observer unregistered (awaitClose-driven count is already 0)"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun engine_stop_doesNotLeaveActiveChildren() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertTrue(engine.engineLifecycleJobForTest?.isActive == true)

            engine.stop()
            assertNull(
                engine.engineLifecycleJobForTest,
                "stop must release the lifecycle job handle"
            )
            assertEquals(0, source.changeSignalSubscriptionCount.value)
            assertEquals(ContactsSyncState.IDLE, engine.state.value)

            engine.stop() // الاستدعاء المتكرر آمن — لا استثناء ولا حالة متغيرة
            assertNull(engine.engineLifecycleJobForTest)
            assertEquals(ContactsSyncState.IDLE, engine.state.value)
        } finally {
            db.closeConnection()
        }
    }
}
