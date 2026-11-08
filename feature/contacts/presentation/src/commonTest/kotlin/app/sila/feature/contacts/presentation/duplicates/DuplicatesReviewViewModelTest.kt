package app.sila.feature.contacts.presentation.duplicates

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Phone
import app.sila.core.model.RecentInteraction
import app.sila.feature.contacts.domain.ContactsMergeStore
import app.sila.feature.contacts.domain.ContactsMergeWriter
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.CreatedPlatformContact
import app.sila.feature.contacts.domain.DismissDuplicateGroupUseCase
import app.sila.feature.contacts.domain.DiscoveredReferences
import app.sila.feature.contacts.domain.MergeContactsUseCase
import app.sila.feature.contacts.domain.MergeRoomCommit
import app.sila.feature.contacts.domain.MergeSnapshotContent
import app.sila.feature.contacts.domain.MergePlanner
import app.sila.feature.contacts.domain.ObserveDuplicateGroupsUseCase
import app.sila.feature.contacts.domain.PlatformMergeRequest
import app.sila.feature.contacts.domain.PlatformMergeResult
import app.sila.feature.contacts.domain.PlatformSweepRequest
import app.sila.feature.contacts.domain.PlatformUnmergeRequest
import app.sila.feature.contacts.domain.PlatformUnmergeResult
import app.sila.feature.contacts.domain.PlatformWritableContact
import app.sila.feature.contacts.domain.SmartFilterWeight
import app.sila.feature.contacts.domain.SweepableSnapshot
import app.sila.feature.contacts.domain.UndoMergeUseCase
import app.sila.feature.contacts.domain.UndoRoomOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات ViewModel شاشة مراجعة المكررات (T7 · Issue #28 — S8b): التجميع الحيّ،
 * المعاينة عند التوسيع حصراً، «دمج كل المؤكدة» = HIGH حصراً، تخطّي المجموعات المتلاشية،
 * والأثر الصادق حين لا يُدمج شيء — فوق حالات الاستخدام الحقيقية بمزايف للمنافذ
 * (نمط MergeUndoUseCaseTest + أسلاك T6: Main مزيف بزمن افتراضي).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicatesReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== المزايف — أمانة العقد (نمط MergeUndoUseCaseTest) =====

    private class FixedClock(var now: Long = 1_000L) : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(now)

        override fun nowEpochMillis(): Long = now
    }

    private class FakeContactsRepository(initial: List<Contact> = emptyList()) : ContactsRepository {
        val state = MutableStateFlow(initial)

        override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> = state
        override fun observeFavorites(): Flow<List<Contact>> = state
        override fun observeCategoryCounts(): Flow<Map<Category, Int>> = MutableStateFlow(emptyMap())
        override suspend fun getContactById(id: Long): Contact? = state.value.find { it.id == id }
        override suspend fun upsertContact(contact: Contact) {}
        override suspend fun updateCategory(contactId: Long, category: Category) {}
        override suspend fun deleteContact(contactId: Long) {}
        override fun searchContacts(query: String): Flow<List<Contact>> = state
        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {}
        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {}
        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {}
        override suspend fun deleteContacts(contactIds: Collection<Long>) {}
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) = Unit
        override fun observeContactById(id: Long): Flow<Contact?> =
            MutableStateFlow(state.value.find { it.id == id })

        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction> = emptyList()
        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) = Unit
        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> = emptyList()
    }

    /** كاتب منصوي مسجِّل — يسمح بحقن فشل الدمج/التراجع ويسجل الناجين المطلوبين. */
    private class RecordingMergeWriter : ContactsMergeWriter {
        val mergedSurvivorSystemIds = mutableListOf<String>()
        var mergeResult: AppResult<PlatformMergeResult> =
            AppResult.Success(PlatformMergeResult("sys_unified", "lk_unified"))
        var unmergeResult: AppResult<PlatformUnmergeResult> =
            AppResult.Success(PlatformUnmergeResult(emptyMap()))

        /** خطاف قبل أول دمج (مرة واحدة) — لمحاكاة المزامنة المتزامنة مع التنفيذ. */
        var onFirstMerge: (() -> Unit)? = null

        /** بوابة تعليق اختيارية داخل الدمج — تتحكم بها خارجها. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult> {
            onFirstMerge?.invoke()
            onFirstMerge = null
            gate?.await()
            mergedSurvivorSystemIds += request.survivor.systemContactId
            return mergeResult
        }

        override suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult> =
            unmergeResult

        override suspend fun sweepAbsorbedRawContacts(request: PlatformSweepRequest): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private class FakeMergeStore : ContactsMergeStore {
        val dismissedKeys = MutableStateFlow<Set<String>>(emptySet())
        val dismissedReasons = mutableListOf<Pair<String, String>>()
        var snapshotIdCounter = 0L
        val activeSnapshots = mutableSetOf<Long>()
        val snapshots = mutableMapOf<Long, MergeSnapshotContent>()
        var undoFailureSnapshotId: Long? = null

        override suspend fun mergeInRoom(
            survivorId: Long,
            mergedContact: Contact,
            absorbedIds: List<Long>,
            groupId: String,
            expiresAt: Long,
            nowMillis: Long,
            buildContent: (DiscoveredReferences) -> MergeSnapshotContent,
        ): MergeRoomCommit {
            val id = ++snapshotIdCounter
            activeSnapshots += id
            snapshots[id] = buildContent(DiscoveredReferences(emptyList(), emptyList()))
            return MergeRoomCommit(id, groupId, emptyList(), emptyList())
        }

        override suspend fun undoInRoom(
            snapshotId: Long,
            nowMillis: Long,
            remap: (MergeSnapshotContent) -> MergeSnapshotContent,
        ): UndoRoomOutcome {
            if (snapshotId == undoFailureSnapshotId) return UndoRoomOutcome.SnapshotNotActive
            if (snapshotId !in activeSnapshots) return UndoRoomOutcome.SnapshotNotActive
            activeSnapshots -= snapshotId
            return UndoRoomOutcome.Restored(MergeSnapshotContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()))
        }

        override suspend fun snapshotContent(snapshotId: Long): MergeSnapshotContent? = snapshots[snapshotId]
        override suspend fun activeSnapshotsForSweep(olderThan: Long): List<SweepableSnapshot> = emptyList()
        override suspend fun markSnapshotExpired(snapshotId: Long) = Unit
        override suspend fun purgeSnapshotsOlderThan(olderThan: Long) = Unit
        override fun observeDismissedPairKeys(): Flow<Set<String>> = dismissedKeys

        override suspend fun dismissPair(groupKey: String, reason: String, nowMillis: Long) {
            dismissedReasons += groupKey to reason
            dismissedKeys.value = dismissedKeys.value + groupKey
        }

        override fun observeFeedback(): Flow<List<SmartFilterWeight>> = MutableStateFlow(emptyList())
        override suspend fun recordFeedback(token: String, category: Category, accepted: Boolean) = Unit
    }

    // ===== مصانع البيانات =====

    private fun contact(id: Long, name: String, phone: String? = null, email: String? = null) = Contact(
        id = id,
        systemContactId = "sys_$id",
        name = ContactName(displayName = name),
        phones = phone?.let { listOf(Phone(raw = it, e164 = it)) } ?: emptyList(),
        emails = email?.let { listOf(Email(address = it)) } ?: emptyList(),
        createdAt = id * 100L,
    )

    private val fixedClock = FixedClock()

    private fun createViewModel(
        repository: FakeContactsRepository,
        writer: RecordingMergeWriter,
        store: FakeMergeStore,
    ): DuplicatesReviewViewModel = DuplicatesReviewViewModel(
        observeGroups = ObserveDuplicateGroupsUseCase(repository, store),
        mergeContacts = MergeContactsUseCase(writer, store, fixedClock),
        undoMerge = UndoMergeUseCase(writer, store, fixedClock),
        dismissGroup = DismissDuplicateGroupUseCase(store, fixedClock),
    )

    /** تجهيز الشاشة: جمع الحالة والآثار بالخلفية ثم دورة زمنية واحدة. */
    private fun TestScope.setupScreen(
        vm: DuplicatesReviewViewModel,
        effects: MutableList<DuplicatesReviewEffect>,
    ) {
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.effects.collect { effects += it } }
        settle()
        runCurrent()
    }

    private fun TestScope.processNow() {
        runCurrent()
        runCurrent()
    }

    /** تصفية كاملة: جميع المهام + دورتا زمن إضافيتان لاستقرار آثار القناة (نمط T6). */
    private fun TestScope.settle() {
        advanceUntilIdle()
        processNow()
    }

    // ===== التجميع الحيّ =====

    @Test
    fun groups_streamCompleteList_andLoadingCompletes() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val vm = createViewModel(repo, RecordingMergeWriter(), FakeMergeStore())
        setupScreen(vm, mutableListOf())

        assertFalse(vm.state.value.isLoading, "التحميل ينتهي بأول انبعاث")
        assertEquals(1, vm.state.value.groups.size)
        assertEquals(2, vm.state.value.confirmedHighGroups.single().contacts.size)
    }

    @Test
    fun dismissedPair_disappearsFromLiveGroups() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val store = FakeMergeStore()
        val vm = createViewModel(repo, RecordingMergeWriter(), store)
        setupScreen(vm, mutableListOf())
        assertEquals(1, vm.state.value.groups.size)

        vm.onIntent(DuplicatesReviewIntent.MarkNotDuplicates(vm.state.value.groups.single().id))
        settle()

        assertTrue(vm.state.value.groups.isEmpty(), "الزوج المرفوض لا يُنشئ حافةً (عقد الكشف)")
        assertEquals(1, store.dismissedReasons.size, "ثنائية واحدة تُسجَّل بمفتاحها الثابت")
        assertEquals("NOT_DUPLICATES", store.dismissedReasons.single().second)
    }

    // ===== المعاينة عند التوسيع =====

    @Test
    fun toggleExpanded_computesPreviewOnlyForExpandedGroup() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
                contact(3, "سارة", email = "sara@example.com"),
                contact(4, "سارة ثانية", email = "sara@example.com"),
            ),
        )
        val vm = createViewModel(repo, RecordingMergeWriter(), FakeMergeStore())
        setupScreen(vm, mutableListOf())
        val groupId = vm.state.value.groups.first().id

        vm.onIntent(DuplicatesReviewIntent.ToggleExpanded(groupId))
        processNow()
        assertTrue(vm.state.value.previews.containsKey(groupId), "التوسيع يحسب معاينة الدمج")

        vm.onIntent(DuplicatesReviewIntent.ToggleExpanded(groupId))
        processNow()
        assertNull(vm.state.value.expandedGroupId)
        assertFalse(vm.state.value.previews.containsKey(groupId), "الطي يُسقط المعاينة")
    }

    @Test
    fun previewPlan_survivorIsOldest_andPhonesUnited() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد إبراهيم", phone = "+201000000001"),
                contact(2, "احمد ابراهيم", phone = "+201000000002"),
            ),
        )
        // الاسم نفسه بعد التطبيع العربي (تطوي الهمزات) → MEDIUM بالاسم حصراً (لا هاتف مشترك)
        val vm = createViewModel(repo, RecordingMergeWriter(), FakeMergeStore())
        setupScreen(vm, mutableListOf())
        val groupId = vm.state.value.groups.first().id

        vm.onIntent(DuplicatesReviewIntent.ToggleExpanded(groupId))
        processNow()
        val plan: MergePlanner.MergePlan = vm.state.value.previews.getValue(groupId)
        assertEquals(100L, plan.merged.createdAt, "الناجي الأقدم إنشاءً (عقد MergePlanner)")
        assertEquals(2, plan.merged.phones.size, "الأرقام تتحاد بلا إسقاط")
    }

    // ===== الدمج الفردي والجماعي =====

    @Test
    fun mergeGroup_success_showsUndoBar_andRecordsSnapshot() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val store = FakeMergeStore()
        val vm = createViewModel(repo, RecordingMergeWriter(), store)
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)
        val groupId = vm.state.value.groups.single().id

        vm.onIntent(DuplicatesReviewIntent.MergeGroup(groupId))
        settle()

        val merged = effects.filterIsInstance<DuplicatesReviewEffect.MergeCompleted>().single()
        assertEquals(1, merged.summary.mergedCount)
        assertTrue(vm.state.value.isUndoBarVisible, "شريط التراجع يظهر بعد الدمج")
        assertEquals(listOf(1L), vm.state.value.undoSnapshotIds)
        assertEquals(setOf(1L), store.activeSnapshots, "لقطة نشطة واحدة تُسجَّل")
    }

    @Test
    fun mergeGroup_failure_emitsMergeFailed_withoutSnapshot() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val writer = RecordingMergeWriter().apply {
            mergeResult = AppResult.Error(DataError.Permission(permissionName = "WRITE_CONTACTS"))
        }
        val store = FakeMergeStore()
        val vm = createViewModel(repo, writer, store)
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(DuplicatesReviewIntent.MergeGroup(vm.state.value.groups.single().id))
        settle()

        assertEquals(
            DuplicatesReviewEffect.ShowError(DuplicateReviewErrorKey.MERGE_FAILED),
            effects.last(),
            "الفشل أثر صادق — لا نجاح زائف",
        )
        assertFalse(vm.state.value.isMerging)
        assertTrue(vm.state.value.undoSnapshotIds.isEmpty(), "فشل الدمج لا يترك لقطة")
        assertTrue(store.activeSnapshots.isEmpty())
        assertFalse(vm.state.value.isUndoBarVisible)
    }

    @Test
    fun mergeAllConfirmed_mergesHighGroupsOnly() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"), // HIGH
                contact(3, "منى", phone = "+201000000002"),
                contact(4, "منى ثانية", phone = "+201000000002"), // HIGH
                contact(5, "سارة إبراهيم"),
                contact(6, "سارة ابراهيم"), // MEDIUM بالاسم حصراً
            ),
        )
        val writer = RecordingMergeWriter()
        val vm = createViewModel(repo, writer, FakeMergeStore())
        setupScreen(vm, mutableListOf())
        assertEquals(3, vm.state.value.groups.size, "مجموعتان مؤكدتان + اقتراح بالاسم")
        assertEquals(2, vm.state.value.confirmedHighGroups.size)

        vm.onIntent(DuplicatesReviewIntent.MergeAllConfirmed)
        settle()

        assertEquals(2, writer.mergedSurvivorSystemIds.size, "الجماعي HIGH حصراً — LOW/MEDIUM لا يُدمجان أبداً")
        assertEquals(2, vm.state.value.lastMergedSummary?.mergedCount)
        assertTrue(vm.state.value.isUndoBarVisible)
    }

    @Test
    fun mergeAll_skipsGroupVanishedMidBatch_andMergesTheRest() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"), // HIGH أ
                contact(3, "منى", phone = "+201000000002"),
                contact(4, "منى ثانية", phone = "+201000000002"), // HIGH ب
            ),
        )
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = RecordingMergeWriter().apply {
            // أثناء الدمج الأول تصل المزامنة فتُذيب كل المجموعات — الثانية تتلاشى قبل قراءتها.
            onFirstMerge = { repo.state.value = emptyList() }
            gate = release
        }
        val vm = createViewModel(repo, writer, FakeMergeStore())
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)
        assertEquals(2, vm.state.value.confirmedHighGroups.size)

        vm.onIntent(DuplicatesReviewIntent.MergeAllConfirmed)
        settle() // يتعطل الدمج الأول عند البوابة ويُفرِّغ الأنبوب الحالة
        release.complete(Unit)
        settle()

        assertEquals(1, writer.mergedSurvivorSystemIds.size, "المجموعة المتلاشية تُتخطى صامتةً")
        assertEquals(1, vm.state.value.lastMergedSummary?.mergedCount)
        assertTrue(vm.state.value.isUndoBarVisible, "ما نُفِّذ فعلاً يملك جلسة تراجع")
        assertNull(effects.filterIsInstance<DuplicatesReviewEffect.ShowError>().firstOrNull())
    }

    // ===== التراجع =====

    @Test
    fun undoMerge_restoresSnapshot_andHidesBar() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val store = FakeMergeStore()
        val vm = createViewModel(repo, RecordingMergeWriter(), store)
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(DuplicatesReviewIntent.MergeGroup(vm.state.value.groups.single().id))
        settle()
        vm.onIntent(DuplicatesReviewIntent.UndoMerge)
        settle()

        assertTrue(effects.contains(DuplicatesReviewEffect.UndoCompleted))
        assertTrue(vm.state.value.undoSnapshotIds.isEmpty())
        assertFalse(vm.state.value.isUndoBarVisible, "التراجع يغلق الجلسة")
        assertTrue(store.activeSnapshots.isEmpty(), "القفل انصرف — اللقطة RESTORED")
    }

    @Test
    fun undoMerge_withoutSnapshot_isSilentNoOp() = runTest(dispatcher) {
        val vm = createViewModel(FakeContactsRepository(emptyList()), RecordingMergeWriter(), FakeMergeStore())
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(DuplicatesReviewIntent.UndoMerge)
        settle()

        assertTrue(effects.isEmpty(), "بلا جلسة دمج — لا أثر ولا كتابة")
    }

    @Test
    fun undoMerge_failure_keepsSessionOpen_andEmitsUndoFailed() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val writer = RecordingMergeWriter().apply {
            unmergeResult = AppResult.Error(DataError.Unknown(causeMessage = "platform refused"))
        }
        val store = FakeMergeStore()
        val vm = createViewModel(repo, writer, store)
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(DuplicatesReviewIntent.MergeGroup(vm.state.value.groups.single().id))
        settle()
        vm.onIntent(DuplicatesReviewIntent.UndoMerge)
        settle()

        assertEquals(DuplicatesReviewEffect.UndoFailed, effects.last())
        assertTrue(vm.state.value.isUndoBarVisible, "فشل التراجع لا يُغلق الجلسة — اللقطة باقية")
        assertEquals(listOf(1L), vm.state.value.undoSnapshotIds)
        assertTrue(store.activeSnapshots.contains(1L), "القفل لم يُصرف")
    }

    @Test
    fun sessionUndo_restoresAllSnapshotsInReverseOrder_andPartialFailureKeepsRemaining() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                // Group 1
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
                // Group 2
                contact(3, "محمود الأول", phone = "+201000000002"),
                contact(4, "محمود الثاني", phone = "+201000000002"),
            ),
        )
        val writer = RecordingMergeWriter()
        val store = FakeMergeStore()
        val vm = createViewModel(repo, writer, store)
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        assertEquals(2, vm.state.value.groups.size)

        // دمج كل المؤكدة (المجموعتان HIGH)
        vm.onIntent(DuplicatesReviewIntent.MergeAllConfirmed)
        settle()

        assertEquals(listOf(1L, 2L), vm.state.value.undoSnapshotIds)
        assertTrue(vm.state.value.isUndoBarVisible)

        // الآن محاكاة فشل في التراجع عن اللقطة 1 (التي هي الثانية في التراجع العكسي 2L ثم 1L)
        // اللقطة 2L ستنجح ثم 1L تفشل
        store.undoFailureSnapshotId = 1L

        vm.onIntent(DuplicatesReviewIntent.UndoMerge)
        settle()

        // يجب أن تفشل عند 1L، وتبقي 1L في undoSnapshotIds والشريط ظاهراً، وتبعث UndoFailed
        assertEquals(DuplicatesReviewEffect.UndoFailed, effects.last())
        assertEquals(listOf(1L), vm.state.value.undoSnapshotIds, "اللقطة التي فشلت تبقى في الجلسة")
        assertTrue(vm.state.value.isUndoBarVisible)

        // الآن بعد إزالة الفشل، إعادة محاولة التراجع تستكمل بنجاح
        store.undoFailureSnapshotId = null
        vm.onIntent(DuplicatesReviewIntent.UndoMerge)
        settle()

        assertTrue(effects.contains(DuplicatesReviewEffect.UndoCompleted))
        assertTrue(vm.state.value.undoSnapshotIds.isEmpty(), "كل اللقطات تم استرجاعها")
        assertFalse(vm.state.value.isUndoBarVisible)
    }

    // ===== التنقل والإغلاق =====

    @Test
    fun back_emitsNavigateBack() = runTest(dispatcher) {
        val vm = createViewModel(FakeContactsRepository(emptyList()), RecordingMergeWriter(), FakeMergeStore())
        val effects = mutableListOf<DuplicatesReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(DuplicatesReviewIntent.Back)
        processNow()

        assertTrue(effects.contains(DuplicatesReviewEffect.NavigateBack))
    }

    @Test
    fun dismissUndoBar_hidesBarWithoutTouchingSnapshot() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "أحمد الأول", phone = "+201000000001"),
                contact(2, "أحمد الثاني", phone = "+201000000001"),
            ),
        )
        val vm = createViewModel(repo, RecordingMergeWriter(), FakeMergeStore())
        setupScreen(vm, mutableListOf())

        vm.onIntent(DuplicatesReviewIntent.MergeGroup(vm.state.value.groups.single().id))
        settle()
        vm.onIntent(DuplicatesReviewIntent.DismissUndoBar)
        processNow()

        assertFalse(vm.state.value.isUndoBarVisible)
        assertEquals(listOf(1L), vm.state.value.undoSnapshotIds, "الإخفاء اليدوي لا يُسقط اللقطة — التراجع يظل ممكناً")
    }
}
