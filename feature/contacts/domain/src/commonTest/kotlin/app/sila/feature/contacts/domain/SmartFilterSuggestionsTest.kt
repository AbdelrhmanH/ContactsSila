package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.core.model.RecentInteraction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * عقود مراجعة الفلترة الذكية (T7 · Issue #28 — agy F6): بوابة smartFilterEnabled،
 * استثناء «لا تسألني مجدداً»، تجميع حتمي بالتصنيف، تثقيل التغذية الراجعة المرفوضة
 * (نزلة درجة)، والتطبيق = كتابة المحدد حصراً + قبول التغذية الراجعة.
 */
class SmartFilterSuggestionsTest {

    /** مزوّد مضيف اختبارات — تنفيذ فوري حتمي (بلا خيوط حقيقية تُفسد الزمن الافتراضي). */
    private val unconfinedDispatchers = object : app.sila.core.common.AppDispatchers {
        override val main = kotlinx.coroutines.Dispatchers.Unconfined
        override val io = kotlinx.coroutines.Dispatchers.Unconfined
        override val default = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private class FixedClock : AppClock {
        override fun now(): kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochMilliseconds(1_000L)
        override fun nowEpochMillis(): Long = 1_000L
    }

    private class FakeContactsRepository(initial: List<Contact> = emptyList()) : ContactsRepository {
        val state = MutableStateFlow(initial)
        val categoryWrites = mutableListOf<Pair<List<Long>, Category>>()
        var failNextCategoriesWrite: Throwable? = null

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
            failNextCategoriesWrite?.let { failure ->
                failNextCategoriesWrite = null
                throw failure
            }
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

    private class FakePreferences(
        initialDismissed: Set<String> = emptySet(),
        initialSmartFilterEnabled: Boolean = true,
    ) : ContactsListPreferencesRepository {
        val dismissed = MutableStateFlow(initialDismissed)
        val smartFilter = MutableStateFlow(initialSmartFilterEnabled)
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
        val feedback = MutableStateFlow<List<SmartFilterWeight>>(emptyList())
        val recorded = mutableListOf<Triple<String, Category, Boolean>>()

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

    private fun contact(
        id: Long,
        name: String,
        category: Category = Category.UNCATEGORIZED,
        excluded: Boolean = false,
        company: String? = null,
        e164: String? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys_$id",
        name = ContactName(displayName = name),
        phones = e164?.let { listOf(Phone(raw = it, e164 = it, isPrimary = true)) } ?: emptyList(),
        emails = emptyList<Email>(),
        category = category,
        organization = company?.let { Organization(company = it) },
        excludeFromSmartFilter = excluded,
    )

    // ——— المُلاحِظ ———

    @Test
    fun observe_gateOff_yieldsNothing() = runTest {
        val useCase = ObserveSmartFilterSuggestionsUseCase(
            FakeContactsRepository(listOf(contact(1, "دكتور أحمد"))),
            FakePreferences(initialSmartFilterEnabled = false),
            FakeMergeStore(),
            unconfinedDispatchers,
        )
        assertTrue(useCase().first().isEmpty(), "البوابة مطفأة — لا تقييم ولا اقتراح (agy F6)")
    }

    @Test
    fun observe_evaluatesUncategorizedOnly_andHonorsDontAsk() = runTest {
        val useCase = ObserveSmartFilterSuggestionsUseCase(
            FakeContactsRepository(
                listOf(
                    contact(1, "دكتور أحمد", e164 = "+201011111111"),
                    contact(2, "أحمد المُصنَّف", category = Category.FRIENDS),
                    contact(3, "صيدلية النور", e164 = "+201022222222", excluded = true),
                ),
            ),
            FakePreferences(),
            FakeMergeStore(),
            unconfinedDispatchers,
        )
        val groups = useCase().first()
        assertEquals(1, groups.size)
        assertEquals(Category.SERVICES, groups.first().category)
        assertEquals(listOf(1L), groups.first().items.map { it.contactId })
    }

    @Test
    fun observe_groupsDeterministically_familyFirst_thenConfidenceWithin() = runTest {
        val useCase = ObserveSmartFilterSuggestionsUseCase(
            FakeContactsRepository(
                listOf(
                    contact(1, "مهندس طارق", company = "شركة النور"), // WORK MEDIUM
                    contact(2, "خوي خالد", e164 = "+201033333333"), // FAMILY HIGH
                    contact(3, "مطعم الأصدقاء", e164 = "+201044444444"), // SERVICES HIGH
                ),
            ),
            FakePreferences(),
            FakeMergeStore(),
            unconfinedDispatchers,
        )
        val groups = useCase().first()
        assertEquals(
            listOf(Category.FAMILY, Category.WORK, Category.SERVICES),
            groups.map { it.category },
            "ترتيب العرض: عائلة، طوارئ، عمل، خدمات — حتمي",
        )
        assertTrue(groups.first { it.category == Category.WORK }.items.single().confidence == SmartFilterConfidence.MEDIUM)
    }

    @Test
    fun observe_rejectedDominantFeedback_downgradesOneLevel() = runTest {
        val store = FakeMergeStore()
        store.feedback.value = listOf(
            SmartFilterWeight(token = "دكتور", category = Category.SERVICES, acceptedCount = 1, rejectedCount = 5),
        )
        val useCase = ObserveSmartFilterSuggestionsUseCase(
            FakeContactsRepository(listOf(contact(1, "دكتور أحمد", e164 = "+201011111111"))),
            FakePreferences(),
            store,
            unconfinedDispatchers,
        )
        val item = useCase().first().single().items.single()
        assertEquals(SmartFilterConfidence.MEDIUM, item.confidence, "الرفض المسيطر يُنزل درجة — لا تحديد مسبق")
    }

    // ——— التطبيق والتغذية الراجعة و«لا تسألني» ———

    @Test
    fun apply_writesOnlySelectedGroupedByCategory_andRecordsAcceptedFeedback() = runTest {
        val repo = FakeContactsRepository()
        val store = FakeMergeStore()
        val apply = ApplySmartFilterSuggestionsUseCase(repo, store)
        val family = SmartFilterItem(1, "خوي خالد", Category.FAMILY, SmartFilterConfidence.HIGH, "خوي")
        val family2 = SmartFilterItem(2, "خوي سامي", Category.FAMILY, SmartFilterConfidence.HIGH, "خوي")
        val service = SmartFilterItem(3, "مطعم النور", Category.SERVICES, SmartFilterConfidence.HIGH, "مطعم")

        apply(listOf(family, family2, service))

        assertEquals(
            listOf(listOf(1L, 2L) to Category.FAMILY, listOf(3L) to Category.SERVICES),
            repo.categoryWrites,
            "كتابة المحدد حصراً — مجمّعة بالتصنيف",
        )
        assertEquals(3, store.recorded.size)
        assertTrue(store.recorded.all { it.third }, "قبول التغذية الراجعة لكل (token,category)")
    }

    @Test
    fun apply_emptySelection_writesNothing() = runTest {
        val repo = FakeContactsRepository()
        val store = FakeMergeStore()
        ApplySmartFilterSuggestionsUseCase(repo, store)(emptyList())
        assertTrue(repo.categoryWrites.isEmpty())
        assertTrue(store.recorded.isEmpty())
    }

    @Test
    fun apply_repositoryFailure_isHonestError_withoutFeedbackRecorded() = runTest {
        val repo = FakeContactsRepository()
        val store = FakeMergeStore()
        repo.failNextCategoriesWrite = IllegalStateException("disk full")
        val result = ApplySmartFilterSuggestionsUseCase(repo, store)(
            listOf(SmartFilterItem(1, "دكتور أحمد", Category.SERVICES, SmartFilterConfidence.HIGH, "دكتور")),
        )

        assertTrue(result is AppResult.Error, "الفشل أثر صادق — لا نجاح زائف")
        assertTrue(store.recorded.isEmpty(), "فشل الكتابة لا يسجل قبولاً — التغذية الراجعة تتبع الكتابة الفعلية")
    }

    @Test
    fun recordFeedback_reject_isWeightedAgainstTokenCategory() = runTest {
        val store = FakeMergeStore()
        val item = SmartFilterItem(7, "دكتور أحمد", Category.SERVICES, SmartFilterConfidence.HIGH, "دكتور")
        RecordSmartFilterFeedbackUseCase(store)(item, accepted = false)
        assertEquals(listOf(Triple("دكتور", Category.SERVICES, false)), store.recorded)
    }

    @Test
    fun setExclude_marksContact_andIsIdempotent_andHandlesMissing() = runTest {
        val repo = FakeContactsRepository(listOf(contact(1, "أحمد"), contact(2, "سامي", excluded = true)))
        val useCase = SetExcludeFromSmartFilterUseCase(repo)

        assertTrue(useCase(1L), "كُتب الاستثناء")
        assertTrue(repo.state.value.first { it.id == 1L }.excludeFromSmartFilter)

        assertTrue(useCase(2L), "مكتوباً سلفاً = نجاح بلا كتابة ثانية")
        assertFalse(useCase(99L), "جهة غائبة = فشل صادق")
    }
}
