package app.sila.feature.contacts.data

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.ContactsSyncBatchResult
import app.sila.core.database.dao.ContactsSyncDao
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات [ContactsSyncEngine] مع `FakeContactsPlatformSource` وقاعدة `:memory:` حقيقية
 * (Issue #23 — Required tests). كل شيء حتمي: بلا `delay()`، وموزعات Unconfined، وبوابات
 * `CompletableDeferred` على غلاف DAO.
 */
class ContactsSyncEngineTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private class FakeClock(var nowMillis: Long = 1_800_000_000_000L) : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(nowMillis)
    }

    /** غلاف DAO ببوابة إيقاف عند النداء رقم [pauseAtCall] + قياس التزامن. */
    private class GatedSyncDao(private val delegate: ContactsSyncDao) : ContactsSyncDao by delegate {
        var pauseAtCall = Int.MAX_VALUE
        val releaseGate = CompletableDeferred<Unit>()
        var enteredCount = 0
            private set
        var maxConcurrent = 0
            private set
        private var inFlight = 0

        override suspend fun applySyncBatch(
            upserts: List<Contact>,
            tombstones: List<String>,
            snapshotJson: String?,
            reconcileStampMillis: Long?,
            nowMillis: Long,
        ): ContactsSyncBatchResult {
            enteredCount++
            inFlight++
            maxConcurrent = max(maxConcurrent, inFlight)
            try {
                if (enteredCount == pauseAtCall) releaseGate.await()
                return delegate.applySyncBatch(upserts, tombstones, snapshotJson, reconcileStampMillis, nowMillis)
            } finally {
                inFlight--
            }
        }
    }

    private fun androidSnapshot(vararg rawIds: Pair<String, Long>): ContactsSnapshot =
        ContactsSnapshot(
            platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
            androidRawContacts = rawIds.associate { (id, version) ->
                id to AndroidRawContactState(version = version)
            }
        )

    private fun contact(
        systemContactId: String,
        displayName: String = systemContactId,
        e164: String = "+20100000000${systemContactId.takeLast(1)}",
    ): app.sila.feature.contacts.domain.PlatformContact =
        app.sila.feature.contacts.domain.PlatformContact(
            systemContactId = systemContactId,
            lookupKey = systemContactId,
            name = ContactName(displayName = displayName),
            phones = listOf(Phone(raw = e164, e164 = e164, isPrimary = true))
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

    @Test
    fun initialImport_batchesOfLimit_firstBatchVisibleBeforeCompletion() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(
                listOf(contact("p1", "أحمد"), contact("p2", "منى")),
                listOf(contact("p3", "سامي"), contact("p4", "ليلى"))
            )
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val gated = GatedSyncDao(db).apply { pauseAtCall = 2 }
            val clock = FakeClock()
            val engine = newEngine(db, source, clock, syncDao = gated, batchLimit = 2)

            val job = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            kotlinx.coroutines.yield()
            // الدفعة الأولى التزمت قبل اكتمال الاستيراد كله (قراءة فورية بعد أول دفعة):
            val visible = db.observeContacts().first()
            assertTrue(visible.any { it.systemContactId == "p1" })
            assertTrue(visible.any { it.systemContactId == "p2" })
            assertTrue(visible.none { it.systemContactId == "p3" })
            assertNull(db.loadSyncSnapshotJson(), "وسط الاستيراد: لا لقطة قبل الدفعة الأخيرة")

            gated.releaseGate.complete(Unit)
            job.join()
            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(4, db.observeContacts().first().count { it.systemContactId.startsWith("p") })
            assertNotNull(db.loadSyncSnapshotJson())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun initialImport_cancellationMidImport_leavesNoSnapshot_andRerunCompletesIdempotently() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(
                listOf(contact("c1"), contact("c2")),
                listOf(contact("c3"), contact("c4"))
            )
            source.snapshotForDelta = androidSnapshot("r" to 1L)
            val gated = GatedSyncDao(db).apply { pauseAtCall = 2 }
            val engine = newEngine(db, source, FakeClock(), syncDao = gated, batchLimit = 2)

            val job = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            kotlinx.coroutines.yield()
            job.cancel()
            gated.releaseGate.complete(Unit)
            kotlinx.coroutines.yield()

            assertEquals(ContactsSyncState.IDLE, engine.state.value, "cancellation is not a failure")
            assertNull(db.loadSyncSnapshotJson(), "no checkpoint advance on cancelled import")

            // إعادة التشغيل تكمل الاستيراد دون تكرار (upsert idempotent بمفتاح systemContactId):
            val second = GatedSyncDao(db)
            val engine2 = newEngine(db, source, FakeClock(), syncDao = second, batchLimit = 2)
            engine2.runSyncCycle(ContactsPermissionState.GRANTED)
            assertEquals(ContactsSyncState.READY, engine2.state.value)
            val imported = db.observeContacts().first().filter { it.systemContactId.startsWith("c") }
            assertEquals(4, imported.size)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun deltaSync_appliesUpserstsAndConfirmedDeletions_withPersistedToken() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("d1"), contact("d2")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val clock = FakeClock()
            val engine = newEngine(db, source, clock)
            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            // دلتا: تحديث d1 + حذف d2 المؤكد من المنصة:
            source.withDeltas(
                ContactsDelta(
                    upserted = listOf(contact("d1", "أحمد بعد التحديث")),
                    deletedSystemContactIds = listOf("d2"),
                    newSnapshot = androidSnapshot("r1" to 2L),
                    isFullResync = false
                )
            )
            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            assertEquals(2, source.readDeltaRequestCount)
            val received = source.readDeltaSnapshots.last()
            assertNotNull(received)
            assertEquals(ContactsSnapshot.PLATFORM_ANDROID, received.platformMarker)

            val all = db.observeContacts().first()
            assertEquals("أحمد بعد التحديث", all.first { it.systemContactId == "d1" }.name.displayName)
            assertTrue(all.none { it.systemContactId == "d2" })
            assertTrue(db.getSyncIndexRows().first { it.systemContactId == "d2" }.systemDeletedAt != null)
            assertEquals(ContactsSyncState.READY, engine.state.value)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun silaColumnsSurviveImportDeltaAndFullReconcile() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("sila_1", "اسم المنصة")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val clock = FakeClock()
            val engine = newEngine(db, source, clock)

            // جهة صِلَة مصنفة قبل أي مزامنة:
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "sila_1",
                    name = ContactName(displayName = "اسم قديم"),
                    category = Category.FAMILY,
                    isFavorite = true,
                    arham = ArhamInfo(relation = Relation.SISTER, frequency = ArhamFrequency.DAILY)
                )
            )

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // استيراد كامل
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // دلتا فارغة
            clock.nowMillis += ContactsSyncEngine.DEFAULT_RECONCILE_INTERVAL_MILLIS
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // مصالحة كاملة مستحقة

            val preserved = db.observeContacts().first().first { it.systemContactId == "sila_1" }
            assertEquals("اسم المنصة", preserved.name.displayName)
            assertEquals(Category.FAMILY, preserved.category)
            assertTrue(preserved.isFavorite)
            assertNotNull(preserved.arham)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun fullResync_underGranted_tombstonesPlatformAbsentRows() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // بذور sys_1..sys_7 موجودة في :memory: — كلها غائبة عن المنصة:
            val source = FakeContactsPlatformSource().withPages(listOf(contact("p_only")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val engine = newEngine(db, source, FakeClock())
            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            val rows = db.getSyncIndexRows()
            assertTrue(rows.none { it.systemContactId.startsWith("sys_") && it.systemDeletedAt == null })
            assertTrue(rows.any { it.systemContactId == "sys_1" && it.systemDeletedAt != null })
            assertEquals(1, db.observeContacts().first().size)
            assertEquals(ContactsSyncState.READY, engine.state.value)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun fullResync_underLimited_neverTombstonesHiddenContacts() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("p_limited")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val engine = newEngine(db, source, FakeClock())
            engine.runSyncCycle(ContactsPermissionState.LIMITED)

            // نقص الظهور الجزئي ليس حذفاً (ADR-0010 §5) — البذور باقية:
            val alive = db.observeContacts().first()
            assertTrue(alive.any { it.systemContactId == "sys_1" })
            assertTrue(alive.any { it.systemContactId == "p_limited" })
            assertTrue(
                db.getSyncIndexRows().none { it.systemDeletedAt != null },
                "LIMITED must never create tombstones"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun limitedDelta_ignoresPlatformDeletions() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("l1"), contact("l2")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val engine = newEngine(db, source, FakeClock())
            engine.runSyncCycle(ContactsPermissionState.LIMITED)

            source.withDeltas(
                ContactsDelta(
                    upserted = emptyList(),
                    deletedSystemContactIds = listOf("l1", "l2"),
                    newSnapshot = androidSnapshot("r1" to 2L),
                    isFullResync = false
                )
            )
            engine.runSyncCycle(ContactsPermissionState.LIMITED)

            val alive = db.observeContacts().first()
            assertTrue(alive.any { it.systemContactId == "l1" })
            assertTrue(alive.any { it.systemContactId == "l2" })
            assertTrue(db.getSyncIndexRows().none { it.systemDeletedAt != null })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun corruptCheckpoint_signalsFullResyncRequired_thenRecoversViaFullImport() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // لقطة فاسدة محفوظة سلفاً:
            db.applySyncBatch(emptyList(), emptyList(), "{corrupt!!", null, 1_800_000_000_000L)

            val source = FakeContactsPlatformSource().withPages(listOf(contact("rec_1")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val gated = GatedSyncDao(db).apply { pauseAtCall = 1 }
            val engine = newEngine(db, source, FakeClock(), syncDao = gated)

            val job = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            kotlinx.coroutines.yield()
            // الإشارة تُعلن قبل بدء الاسترجاع التلقائي (والمزامنة متوقفة عند أول دفعة):
            assertEquals(ContactsSyncState.FULL_RESYNC_REQUIRED, engine.state.value)
            gated.releaseGate.complete(Unit)
            job.join()

            // الاسترجاع التلقائي: قراءة كاملة (snapshot = null) لا دلتا:
            assertEquals(listOf<ContactsSnapshot?>(null), source.readDeltaSnapshots)
            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertNotNull(db.loadSyncSnapshotJson())
            assertEquals(1, db.observeContacts().first().count { it.systemContactId == "rec_1" })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun weeklyReconcileGate_runsDeltaWhenFresh_andFullResyncWhenDue() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("w1")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val clock = FakeClock()
            val engine = newEngine(db, source, clock)

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // استيراد أول (readDelta(null))
            source.withDeltas(
                ContactsDelta(emptyList(), emptyList(), androidSnapshot("r1" to 2L), isFullResync = false)
            )
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // دلتا (ختم حديث)
            assertEquals(
                ContactsSnapshot.PLATFORM_ANDROID,
                source.readDeltaSnapshots.last()?.platformMarker,
                "fresh reconcile stamp → delta, not full resync"
            )

            clock.nowMillis += ContactsSyncEngine.DEFAULT_RECONCILE_INTERVAL_MILLIS + 1
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // مصالحة مستحقة
            assertNull(source.readDeltaSnapshots.last(), "due reconcile → full resync readDelta(null)")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun deltaWithoutSnapshot_failsAndCommitsNothing() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("f1")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val clock = FakeClock()
            val engine = newEngine(db, source, clock)
            engine.runSyncCycle(ContactsPermissionState.GRANTED)
            val snapshotBefore = db.loadSyncSnapshotJson()

            source.withDeltas(
                ContactsDelta(
                    upserted = listOf(contact("f1", "محدّث")),
                    deletedSystemContactIds = listOf("f1"),
                    newSnapshot = null, // فشل إنتاج اللقطة (عقد المنصة عند الأخطاء)
                    isFullResync = false
                )
            )
            engine.runSyncCycle(ContactsPermissionState.GRANTED)

            assertEquals(ContactsSyncState.FAILED, engine.state.value)
            assertEquals(snapshotBefore, db.loadSyncSnapshotJson(), "no checkpoint advance on failure")
            assertEquals("f1", db.observeContacts().first().single { it.systemContactId == "f1" }.systemContactId)
            assertTrue(db.getSyncIndexRows().single { it.systemContactId == "f1" }.systemDeletedAt == null)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun concurrentCycles_areSerializedBySingleMutex() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val source = FakeContactsPlatformSource().withPages(listOf(contact("m1")))
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val gated = GatedSyncDao(db)
            val engine = newEngine(db, source, FakeClock(), syncDao = gated)

            val first = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            kotlinx.coroutines.yield()
            val second = launch { engine.runSyncCycle(ContactsPermissionState.GRANTED) }
            kotlinx.coroutines.yield()

            assertTrue(gated.maxConcurrent <= 1, "cycles must never interleave inside the DAO")
            gated.releaseGate.complete(Unit)
            first.join()
            second.join()
            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(1, gated.maxConcurrent)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun permissionTransitions_deniedStops_grantRunsFullImport_regrantAfterDenialAgain() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = FakeContactsPlatformSource(initialPermission = ContactsPermissionState.DENIED)
        source.withPages(listOf(contact("g1")))
        source.snapshotForDelta = androidSnapshot("r1" to 1L)
        val engine = newEngine(db, source, FakeClock())
        // نطاق Unconfined: المراقبة تعالج القيمة الأولى تزامنياً داخل start() نفسها،
        // وكل انبعاث للقيمة يعالَج تزامنياً لحظة الإصدار — حتمية كاملة.
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            assertEquals(ContactsSyncState.PERMISSION_DENIED, engine.state.value)

            source.grantPermission()
            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertNull(source.readDeltaSnapshots.single(), "grant-after-denial → full import readDelta(null)")
            assertEquals(1, db.observeContacts().first().count { it.systemContactId == "g1" })

            source.denyPermission()
            assertEquals(ContactsSyncState.PERMISSION_DENIED, engine.state.value)

            source.grantPermission()
            assertNull(source.readDeltaSnapshots.last(), "second grant-after-denial → full import again")
            assertEquals(2, source.readDeltaRequestCount)
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun start_isIdempotent_noDuplicateCollectors() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val source = FakeContactsPlatformSource().withPages(listOf(contact("i1")))
        source.snapshotForDelta = androidSnapshot("r1" to 1L)
        val engine = newEngine(db, source, FakeClock())
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            engine.start(engineScope)
            engine.start(engineScope) // البدء المزدوج لا يرمي ولا يكرر المراقبة
            source.grantPermission()

            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(1, source.readDeltaRequestCount)
        } finally {
            engine.stop()
            db.closeConnection()
        }
    }

    @Test
    fun phoneUnionPropertyTest_unionOfUniquePhonesPreservedAcrossSyncs() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val seed = 42L
            val random = Random(seed)
            fun randomE164(index: Int): String =
                "+2010" + (10000000 + random.nextInt(90_000_000)).toString() + index.toString().padStart(2, '0')

            // مجموعة منصة عشوائية بأرقام فريدة:
            val platformContacts = (0 until 40).map { index ->
                contact("prop_$index", "جهة $index", e164 = randomE164(index))
            }
            val platformPhones = platformContacts
                .flatMap { it.phones.map { p -> p.e164 } }
                .toSet()
            // الرقم البديل لـ prop_0 في الدلتا — يُلتقط مرة واحدة (حتمية التسلسل العشوائي):
            val prop0ReplacementPhone = randomE164(100)

            val source = FakeContactsPlatformSource().withPages(*platformContacts.chunked(30).toTypedArray())
            source.snapshotForDelta = androidSnapshot("r1" to 1L)
            val clock = FakeClock()
            val engine = newEngine(db, source, clock, batchLimit = 7)

            // جهة صِلَة برقم خاص بها يجب أن يبقى:
            val silaPhone = "+20999999999"
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "sila_prop",
                    name = ContactName(displayName = "جهة صِلَة"),
                    phones = listOf(Phone(raw = "0999999999", e164 = silaPhone, isPrimary = true))
                )
            )

            engine.runSyncCycle(ContactsPermissionState.GRANTED) // استيراد
            source.withDeltas(
                ContactsDelta(
                    upserted = listOf(
                        contact("prop_0", "جهة 0 محدثة", e164 = prop0ReplacementPhone)
                    ),
                    deletedSystemContactIds = emptyList(),
                    newSnapshot = androidSnapshot("r1" to 2L),
                    isFullResync = false
                )
            )
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // دلتا تستبدل رقم prop_0

            // المنصة نفسها عكست التغيير قبل المصالحة (الواقع: الصفحة تعيد الحالة الجديدة):
            source.withPages(
                *platformContacts
                    .map { c ->
                        if (c.systemContactId == "prop_0") {
                            contact("prop_0", "جهة 0 محدثة", e164 = prop0ReplacementPhone)
                        } else {
                            c
                        }
                    }
                    .chunked(30).toTypedArray()
            )
            clock.nowMillis += ContactsSyncEngine.DEFAULT_RECONCILE_INTERVAL_MILLIS
            engine.runSyncCycle(ContactsPermissionState.GRANTED) // مصالحة كاملة

            val roomPhones = db.observeContacts().first()
                .flatMap { it.phones.map { p -> p.e164 } }
                .toSet()

            // لا فقدان ولا اختراع في جهات المنصة المعكوسة:
            val prop0OldPhone = platformContacts.first().phones.single().e164
            val expected = platformPhones - prop0OldPhone + prop0ReplacementPhone
            assertEquals(expected, roomPhones)
            // جهة صِلَة المحلية-only غير الموجودة في المنصة تُقبَر بمصالحة المرآة الكاملة
            // (ADR-0010 §4 — المنصة مرجعية) بلا حذف فعلي:
            val silaRow = db.getSyncIndexRows().first { it.systemContactId == "sila_prop" }
            assertTrue(silaRow.systemDeletedAt != null)
        } finally {
            db.closeConnection()
        }
    }
}
