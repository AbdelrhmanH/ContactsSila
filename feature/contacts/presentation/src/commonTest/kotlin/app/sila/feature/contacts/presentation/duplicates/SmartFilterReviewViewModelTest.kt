package app.sila.feature.contacts.presentation.duplicates

import app.sila.core.common.AppResult
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.Email
import app.sila.core.model.Phone
import app.sila.core.model.RecentInteraction
import app.sila.core.model.Relation
import app.sila.feature.contacts.domain.ApplySmartFilterSuggestionsUseCase
import app.sila.feature.contacts.domain.ArhamKinshipLinker
import app.sila.feature.contacts.domain.ContactsListPreferencesRepository
import app.sila.feature.contacts.domain.ContactsMergeStore
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.DiscoveredReferences
import app.sila.feature.contacts.domain.MergeRoomCommit
import app.sila.feature.contacts.domain.MergeSnapshotContent
import app.sila.feature.contacts.domain.ObserveSmartFilterSuggestionsUseCase
import app.sila.feature.contacts.domain.RecordSmartFilterFeedbackUseCase
import app.sila.feature.contacts.domain.SetExcludeFromSmartFilterUseCase
import app.sila.feature.contacts.domain.SmartFilterWeight
import app.sila.feature.contacts.domain.SwipeActionConfig
import app.sila.feature.contacts.domain.SweepableSnapshot
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
 * اختبارات ViewModel شاشة مراجعة الفلترة الذكية (T7 · Issue #28 — S9b): التحديد
 * المسبق HIGH حصراً، حفظ تحديد المستخدم عبر الانبعاثات، رفض التغذية الراجعة،
 * «لا تسألني مجدداً»، والتطبيق الصادق — فوق حالات الاستخدام الحقيقية بمزايف للمنافذ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartFilterReviewViewModelTest {

    /** مزوّد مضيف اختبارات — تنفيذ فوري حتمي (بلا خيوط حقيقية تُفسد الزمن الافتراضي). */
    private val unconfinedDispatchers = object : app.sila.core.common.AppDispatchers {
        override val main = kotlinx.coroutines.Dispatchers.Unconfined
        override val io = kotlinx.coroutines.Dispatchers.Unconfined
        override val default = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== المزايف (نمط اختبارات مراجعة المكررات) =====

    private class FakeContactsRepository(initial: List<Contact> = emptyList()) : ContactsRepository {
        val state = MutableStateFlow(initial)
        val categoryWrites = mutableListOf<Pair<List<Long>, Category>>()

        override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> = state
        override fun observeFavorites(): Flow<List<Contact>> = state
        override fun observeCategoryCounts(): Flow<Map<Category, Int>> = MutableStateFlow(emptyMap())
        override suspend fun getContactById(id: Long): Contact? = state.value.find { it.id == id }
        override suspend fun upsertContact(contact: Contact) {
            state.value = state.value.map { if (it.id == contact.id) contact else it }
        }
        override suspend fun updateCategory(contactId: Long, category: Category) {}
        override suspend fun deleteContact(contactId: Long) {}
        override fun searchContacts(query: String): Flow<List<Contact>> = state
        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {}
        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {
            categoryWrites += contactIds.toList() to category
        }
        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {}
        override suspend fun deleteContacts(contactIds: Collection<Long>) {}
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) = Unit
        override fun observeContactById(id: Long): Flow<Contact?> =
            MutableStateFlow(state.value.find { it.id == id })

        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction> = emptyList()
        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) = Unit
        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> = emptyList()
    }

    private class FakePreferences : ContactsListPreferencesRepository {
        val dismissed = MutableStateFlow(emptySet<String>())
        val smartFilter = MutableStateFlow(true)
        override val dismissedSuggestionIds: Flow<Set<String>> = dismissed
        override suspend fun dismissSuggestion(id: String) {
            dismissed.value = dismissed.value + id
        }
        override val smartFilterEnabled: Flow<Boolean> = smartFilter
        override val swipeActions: Flow<SwipeActionConfig> =
            MutableStateFlow(SwipeActionConfig(ContactSwipeAction.CALL, ContactSwipeAction.MESSAGE))
        override suspend fun setSwipeActions(config: SwipeActionConfig) {}
    }

    private class FakeMergeStore : ContactsMergeStore {
        val feedback = MutableStateFlow(emptyList<SmartFilterWeight>())
        val recorded = mutableListOf<Triple<String, Category, Boolean>>()
        val excludedNow = MutableStateFlow(emptySet<Long>())

        override suspend fun mergeInRoom(
            survivorId: Long,
            mergedContact: Contact,
            absorbedIds: List<Long>,
            groupId: String,
            expiresAt: Long,
            nowMillis: Long,
            buildContent: (DiscoveredReferences) -> MergeSnapshotContent,
        ): MergeRoomCommit = error("not used here")

        override suspend fun undoInRoom(
            snapshotId: Long,
            nowMillis: Long,
            remap: (MergeSnapshotContent) -> MergeSnapshotContent,
        ): UndoRoomOutcome = error("not used here")

        override suspend fun snapshotContent(snapshotId: Long): MergeSnapshotContent? = null
        override suspend fun activeSnapshotsForSweep(olderThan: Long): List<SweepableSnapshot> = emptyList()
        override suspend fun markSnapshotExpired(snapshotId: Long) = Unit
        override suspend fun purgeSnapshotsOlderThan(olderThan: Long) = Unit
        override fun observeDismissedPairKeys(): Flow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun dismissPair(groupKey: String, reason: String, nowMillis: Long) = Unit
        override fun observeFeedback(): Flow<List<SmartFilterWeight>> = feedback

        override suspend fun recordFeedback(token: String, category: Category, accepted: Boolean) {
            recorded += Triple(token, category, accepted)
        }
    }

    private class FakeArhamKinshipLinker : ArhamKinshipLinker {
        val linked = mutableListOf<Pair<Long, Relation>>()
        var shouldFail = false

        override suspend fun link(contactId: Long, relation: Relation): AppResult<Unit> {
            return if (shouldFail) {
                AppResult.Error(app.sila.core.common.DataError.Unknown("link failed"))
            } else {
                linked += contactId to relation
                AppResult.Success(Unit)
            }
        }
    }

    // ===== مصانع =====

    private fun contact(id: Long, name: String, e164: String? = null, excluded: Boolean = false) = Contact(
        id = id,
        systemContactId = "sys_$id",
        name = ContactName(displayName = name),
        phones = e164?.let { listOf(Phone(raw = it, e164 = it, isPrimary = true)) } ?: emptyList(),
        emails = emptyList<Email>(),
        excludeFromSmartFilter = excluded,
        createdAt = id * 100L,
    )

    private fun createViewModel(
        repository: FakeContactsRepository,
        store: FakeMergeStore,
        kinshipLinker: ArhamKinshipLinker = FakeArhamKinshipLinker(),
    ): SmartFilterReviewViewModel = SmartFilterReviewViewModel(
        observeSuggestions = ObserveSmartFilterSuggestionsUseCase(repository, FakePreferences(), store, unconfinedDispatchers),
        applySuggestions = ApplySmartFilterSuggestionsUseCase(repository, store),
        recordFeedback = RecordSmartFilterFeedbackUseCase(store),
        setExclude = SetExcludeFromSmartFilterUseCase(repository),
        kinshipLinker = kinshipLinker,
    )

    private fun TestScope.setupScreen(
        vm: SmartFilterReviewViewModel,
        effects: MutableList<SmartFilterReviewEffect>,
    ) {
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.effects.collect { effects += it } }
        settle()
    }

    private fun TestScope.processNow() {
        runCurrent()
        runCurrent()
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        processNow()
    }

    // ===== التحديد المسبق والانبعاثات =====

    @Test
    fun firstEmission_preselectsHighOnly_andCompletesLoading() = runTest(dispatcher) {
        val vm = createViewModel(
            FakeContactsRepository(
                listOf(
                    contact(1, "خوي خالد", e164 = "+201011111111"), // FAMILY HIGH
                    contact(2, "مهندس طارق", e164 = "+201022222222"), // WORK MEDIUM
                ),
            ),
            FakeMergeStore(),
        )
        setupScreen(vm, mutableListOf())

        assertFalse(vm.state.value.isLoading)
        assertEquals(setOf(1L), vm.state.value.checkedIds, "HIGH حصراً يُحدد مسبقاً — MEDIUM لا")
        assertEquals(2, vm.state.value.visibleItems.size)
    }

    @Test
    fun laterEmission_preservesUserChecked_andPrunesVanishedItems() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "خوي خالد", e164 = "+201011111111"),
                contact(2, "مهندس طارق", e164 = "+201022222222"), // MEDIUM غير محدد
            ),
        )
        val vm = createViewModel(repo, FakeMergeStore())
        setupScreen(vm, mutableListOf())
        assertEquals(setOf(1L), vm.state.value.checkedIds)

        vm.onIntent(SmartFilterReviewIntent.ToggleItem(2L)) // المستخدم أضاف MEDIUM
        processNow()
        assertEquals(setOf(1L, 2L), vm.state.value.checkedIds)

        // المزامنة تلغي الحاجة لبندٍ وتضيف بنداً جديداً — تحديد المستخدم الباقي محفوظ،
        // والبند الجديد لا يُحدد آلياً (التحديد المسبق أول ظهور حصراً)
        repo.state.value = listOf(contact(2, "مهندس طارق", e164 = "+201022222222"), contact(3, "مطعم النور", e164 = "+201033333333"))
        settle()

        assertEquals(setOf(2L), vm.state.value.checkedIds, "تحديد المستخدم محفوظ والمختفي مُقص")
        assertEquals(2, vm.state.value.visibleItems.size)
    }

    @Test
    fun toggleGroupAll_checksWholeGroup_andSecondTapClearsIt() = runTest(dispatcher) {
        val vm = createViewModel(
            FakeContactsRepository(
                listOf(
                    contact(1, "خوي خالد", e164 = "+201011111111"),
                    contact(2, "خوي سامي", e164 = "+201022222222"),
                ),
            ),
            FakeMergeStore(),
        )
        setupScreen(vm, mutableListOf())
        assertEquals(setOf(1L, 2L), vm.state.value.checkedIds, "ثنائية HIGH — محددة مسبقاً كليهما")

        vm.onIntent(SmartFilterReviewIntent.ToggleItem(1L))
        processNow()
        assertEquals(setOf(2L), vm.state.value.checkedIds)

        vm.onIntent(SmartFilterReviewIntent.ToggleGroupAll(Category.FAMILY))
        processNow()
        assertEquals(setOf(1L, 2L), vm.state.value.checkedIds, "ليست كاملة → تحديد الكل")

        vm.onIntent(SmartFilterReviewIntent.ToggleGroupAll(Category.FAMILY))
        processNow()
        assertEquals(emptySet(), vm.state.value.checkedIds, "كاملة → إلغاء تحديد الكل")
    }

    // ===== التطبيق =====

    @Test
    fun apply_success_writesCheckedOnly_andNavigatesBack() = runTest(dispatcher) {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, "دكتور أحمد", e164 = "+201011111111"), // SERVICES HIGH محدد مسبقاً (بلا مرشح قرابة)
                contact(2, "مهندس طارق", e164 = "+201022222222"), // WORK MEDIUM غير محدد
            ),
        )
        val store = FakeMergeStore()
        val vm = createViewModel(repo, store)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.ToggleItem(2L)) // المستخدم أضاف MEDIUM
        processNow()
        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()

        assertTrue(effects.contains(SmartFilterReviewEffect.NavigateBack), "النجاح يعود للقائمة مباشرة عند عدم وجود عروض أرحام")
        assertEquals(
            listOf(
                listOf(2L) to Category.WORK,
                listOf(1L) to Category.SERVICES,
            ),
            repo.categoryWrites,
            "المحدد حصراً — مجمّع بالتصنيف",
        )
        assertEquals(2, store.recorded.size)
        assertTrue(store.recorded.all { it.third })
    }

    @Test
    fun apply_failure_emitsShowError_andResetsApplying() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "دكتور أحمد", e164 = "+201011111111")))
        val store = FakeMergeStore()
        // كتابة تصنيفات مكسورة — الفشل أثر صادق
        val brokenVm = SmartFilterReviewViewModel(
            observeSuggestions = ObserveSmartFilterSuggestionsUseCase(repo, FakePreferences(), store, unconfinedDispatchers),
            applySuggestions = ApplySmartFilterSuggestionsUseCase(
                object : ContactsRepository by repo {
                    override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {
                        error("disk full")
                    }
                },
                store,
            ),
            recordFeedback = RecordSmartFilterFeedbackUseCase(store),
            setExclude = SetExcludeFromSmartFilterUseCase(repo),
            kinshipLinker = FakeArhamKinshipLinker(),
        )
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(brokenVm, effects)

        brokenVm.onIntent(SmartFilterReviewIntent.Apply)
        settle()

        assertEquals(
            SmartFilterReviewEffect.ShowError(SmartFilterReviewErrorKey.APPLY_FAILED),
            effects.last(),
        )
        assertFalse(brokenVm.state.value.isApplying)
        assertTrue(brokenVm.state.value.hasError)
    }

    @Test
    fun apply_familyWithRelation_triggersPendingArhamOffer_andWaitsForDecision() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111")))
        val linker = FakeArhamKinshipLinker()
        val vm = createViewModel(repo, FakeMergeStore(), linker)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()

        assertEquals(1, vm.state.value.pendingArhamOffer.size)
        assertEquals(1L, vm.state.value.pendingArhamOffer.first().contactId)
        assertEquals(Relation.BROTHER, vm.state.value.pendingArhamOffer.first().relationCandidate)
        assertFalse(effects.contains(SmartFilterReviewEffect.NavigateBack), "لا رجوع قبل قرار عرض أرحام")
        assertTrue(linker.linked.isEmpty(), "لم يتم الربط بعد")
    }

    @Test
    fun confirmArhamOffer_linksAllOffersWithDefaultFrequency_andNavigatesBack() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111")))
        val linker = FakeArhamKinshipLinker()
        val vm = createViewModel(repo, FakeMergeStore(), linker)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()
        vm.onIntent(SmartFilterReviewIntent.ConfirmArhamOffer)
        settle()

        assertEquals(listOf(1L to Relation.BROTHER), linker.linked)
        assertTrue(vm.state.value.pendingArhamOffer.isEmpty())
        assertTrue(effects.contains(SmartFilterReviewEffect.NavigateBack))
    }

    @Test
    fun confirmArhamOffer_failure_emitsShowError_andNavigatesBack() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111")))
        val linker = FakeArhamKinshipLinker().apply { shouldFail = true }
        val vm = createViewModel(repo, FakeMergeStore(), linker)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()
        vm.onIntent(SmartFilterReviewIntent.ConfirmArhamOffer)
        settle()

        assertTrue(effects.contains(SmartFilterReviewEffect.ShowError(SmartFilterReviewErrorKey.ARHAM_LINK_FAILED)))
        assertTrue(effects.contains(SmartFilterReviewEffect.NavigateBack))
        assertTrue(vm.state.value.pendingArhamOffer.isEmpty())
    }

    @Test
    fun declineArhamOffer_navigatesBackWithoutLinking() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111")))
        val linker = FakeArhamKinshipLinker()
        val vm = createViewModel(repo, FakeMergeStore(), linker)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()
        vm.onIntent(SmartFilterReviewIntent.DeclineArhamOffer)
        settle()

        assertTrue(linker.linked.isEmpty(), "رفض العرض لا يربط أرحام")
        assertTrue(vm.state.value.pendingArhamOffer.isEmpty())
        assertTrue(effects.contains(SmartFilterReviewEffect.NavigateBack))
    }

    @Test
    fun apply_emptyChecked_isNoOp() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(2, "مهندس طارق", e164 = "+201022222222")))
        val store = FakeMergeStore()
        val vm = createViewModel(repo, store)
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects) // لا HIGH — لا تحديد مسبق

        vm.onIntent(SmartFilterReviewIntent.Apply)
        settle()

        assertTrue(effects.isEmpty(), "لا شيء محدد — لا نية ولا أثر")
        assertTrue(repo.categoryWrites.isEmpty())
        assertFalse(vm.state.value.isApplying)
    }

    // ===== الرفض و«لا تسألني» =====

    @Test
    fun notThisCategory_recordsRejectedFeedback_andHidesItem() = runTest(dispatcher) {
        val vm = createViewModel(
            FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111"))),
            FakeMergeStore(),
        )
        setupScreen(vm, mutableListOf())
        assertEquals(1, vm.state.value.visibleItems.size)

        vm.onIntent(SmartFilterReviewIntent.NotThisCategory(1L))
        settle()

        assertTrue(vm.state.value.visibleItems.isEmpty(), "البند المرفوض يختفي من العرض")
        assertEquals(1, vm.state.value.rejectedIds.size)
    }

    @Test
    fun neverAsk_writesExcludeFlag_andItemLeavesStream() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact(1, "خوي خالد", e164 = "+201011111111")))
        val vm = createViewModel(repo, FakeMergeStore())
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.NeverAsk(1L))
        settle()

        assertTrue(repo.state.value.single().excludeFromSmartFilter, "علم «لا تسألني» مكتوب في Room")
        assertTrue(vm.state.value.visibleItems.isEmpty(), "البند المستثنى يخرج من التيار ذاتياً")
        assertNull(effects.filterIsInstance<SmartFilterReviewEffect.ShowError>().firstOrNull())
    }

    // ===== التنقل =====

    @Test
    fun back_emitsNavigateBack() = runTest(dispatcher) {
        val vm = createViewModel(FakeContactsRepository(emptyList()), FakeMergeStore())
        val effects = mutableListOf<SmartFilterReviewEffect>()
        setupScreen(vm, effects)

        vm.onIntent(SmartFilterReviewIntent.Back)
        processNow()

        assertTrue(effects.contains(SmartFilterReviewEffect.NavigateBack))
    }
}
