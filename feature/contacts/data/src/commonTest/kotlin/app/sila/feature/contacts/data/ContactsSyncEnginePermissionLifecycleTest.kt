package app.sila.feature.contacts.data

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات دورة حياة الإشارات المقيَّدة بالصلاحية في [ContactsSyncEngine]
 * (DEF-01 · Issue #43 — النطاق B): الاشتراك بالإشارات فقط عند توفر صلاحية
 * القراءة، والإلغاء عند السحب (خروج المراقب عبر awaitClose في بوابة المصدر)،
 * وإعادة الاشتراك التلقائية عند الاستعادة بلا إعادة تشغيل العملية، وبلا
 * جامعين متوازيين أو مزامنة غير مصرّح بها. كل شيء حتمي: بلا `delay()`،
 * وموزعات Unconfined، وقاعدة `:memory:` حقيقية — بنفس أحداد اختبارات المحرك.
 */
class ContactsSyncEnginePermissionLifecycleTest {

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

    private fun newEngine(
        db: LocalSilaDatabase,
        source: FakeContactsPlatformSource,
    ): ContactsSyncEngine = ContactsSyncEngine(
        platformSource = source,
        syncDao = db,
        clock = FakeClock()
    )

    private fun grantedSourceWithOneContact(): FakeContactsPlatformSource =
        FakeContactsPlatformSource(initialPermission = ContactsPermissionState.GRANTED)
            .withPages(listOf(FakeContactsPlatformSource.contact("c1")))
            .apply { snapshotForDelta = androidSnapshot("r1" to 1L) }

    @Test
    fun engine_deniedPermission_doesNotCollectSignals() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        // الافتراضي في البديل: DENIED (تثبيت جديد — DEF-01).
        val source = FakeContactsPlatformSource().withPages(listOf(FakeContactsPlatformSource.contact("c1")))
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            runCurrent()

            assertEquals(
                ContactsSyncState.PERMISSION_DENIED,
                engine.state.value,
                "denied permission must surface PERMISSION_DENIED"
            )
            assertEquals(
                0,
                source.changeSignalSubscriptionCount.value,
                "no signals collection may start while the read permission is denied"
            )
            assertEquals(
                0,
                source.readDeltaRequestCount,
                "no unauthorized reads while the read permission is denied"
            )
            runCurrent()
            assertEquals(
                ContactsSyncState.PERMISSION_DENIED,
                engine.state.value,
                "engine scope stays alive and keeps its denied state"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_grantTransition_resyncsAndStartsSignals() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = FakeContactsPlatformSource().withPages(listOf(FakeContactsPlatformSource.contact("c1")))
        source.snapshotForDelta = androidSnapshot("r1" to 1L)
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope) // DENIED افتراضياً — لا مزامنة ولا اشتراك
            runCurrent()
            assertEquals(0, source.changeSignalSubscriptionCount.value)

            source.grantPermission() // DENIED → GRANTED: مزامنة قسرية + بدء الإشارات
            assertEquals(
                ContactsSyncState.READY,
                engine.state.first { it == ContactsSyncState.READY },
                "granting the permission must sync without a process restart"
            )
            assertEquals(
                1,
                source.changeSignalSubscriptionCount.value,
                "signals observation must start automatically on the grant transition"
            )
            assertEquals(
                1,
                source.readDeltaRequestCount,
                "the initial import runs once for the first grant (previous = DENIED → not a forced resync)"
            )

            source.denyPermission()
            runCurrent()
            source.grantPermission() // DENIED → GRANTED مرة ثانية: مصالحة قسرية (السلوك الحالي)
            assertEquals(
                ContactsSyncState.READY,
                engine.state.first { it == ContactsSyncState.READY }
            )
            assertTrue(
                source.readDeltaSnapshots.lastOrNull() == null,
                "the DENIED → GRANTED transition must force a full resync (readDelta(null)) — existing behavior"
            )
            assertEquals(
                1,
                source.changeSignalSubscriptionCount.value,
                "exactly one active observer after the transition (no duplicates)"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_permissionRevoked_cancelsSignals() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(
                1,
                source.changeSignalSubscriptionCount.value,
                "observation active while granted"
            )
            val readsBefore = source.readDeltaRequestCount

            source.denyPermission()
            runCurrent()

            assertEquals(
                0,
                source.changeSignalSubscriptionCount.value,
                "revocation must cancel the signals collection (observer unregistered via awaitClose)"
            )
            assertEquals(
                ContactsSyncState.PERMISSION_DENIED,
                engine.state.value
            )

            source.sendChangeSignal()
            runCurrent()
            assertEquals(
                readsBefore,
                source.readDeltaRequestCount,
                "a stale change signal after revocation must not trigger any sync (no unauthorized read)"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun engine_repeatedPermissionTransitions_doNotDuplicateCollectors() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = grantedSourceWithOneContact()
        val engine = newEngine(db, source)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            val expectedSequence = listOf(1, 0, 1, 0, 1) // GRANTED → DENIED → GRANTED → DENIED → GRANTED
            assertEquals(expectedSequence[0], source.changeSignalSubscriptionCount.value)

            source.denyPermission(); runCurrent()
            assertEquals(expectedSequence[1], source.changeSignalSubscriptionCount.value)

            source.grantPermission(); runCurrent()
            assertEquals(expectedSequence[2], source.changeSignalSubscriptionCount.value)

            source.denyPermission(); runCurrent()
            assertEquals(expectedSequence[3], source.changeSignalSubscriptionCount.value)

            source.grantPermission(); runCurrent()
            assertEquals(expectedSequence[4], source.changeSignalSubscriptionCount.value)

            assertEquals(
                1,
                source.changeSignalSubscriptionCount.value,
                "repeated transitions must never exceed one active signals collector"
            )
            assertTrue(
                source.readDeltaSnapshots.count { it == null } >= 2,
                "each DENIED → GRANTED transition forces its full resync (no lost updates across the gap)"
            )
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }
}
