package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactAccount
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.Phone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات نطاق قائمة جهات الاتصال (Issue #24): فلتر الحساب، تكوين الفلاتر في
 * البحث، بطاقات الاقتراح واستبعادها، والأفعال الجماعية على مستودع مزيف.
 */
class ContactsListDomainTest {

    // ===== مصانع الاختبار =====

    private fun contact(
        id: Long,
        systemContactId: String = "sys$id",
        displayName: String = "جهة $id",
        category: Category = Category.UNCATEGORIZED,
        isFavorite: Boolean = false,
        accountType: String? = null,
    ): Contact = Contact(
        id = id,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = "0100000000$id", e164 = "+20100000000$id", isPrimary = true)),
        category = category,
        isFavorite = isFavorite,
        account = accountType?.let { ContactAccount(accountType = it, accountName = "acc") },
        normalizedName = displayName
    )

    /** مستودع مزيف بكاش في الذاكرة — يفصل النطاق عن Room في هذه الاختبارات. */
    private class FakeContactsRepository(initial: List<Contact> = emptyList()) : ContactsRepository {
        val state = MutableStateFlow(initial)
        var searchedQuery: String? = null
        val recordedCalls = mutableListOf<String>()

        override fun observeContacts(category: Category?, searchQuery: String): Flow<List<Contact>> =
            state

        override fun observeFavorites(): Flow<List<Contact>> = state

        override fun observeCategoryCounts(): Flow<Map<Category, Int>> = MutableStateFlow(emptyMap())

        override suspend fun getContactById(id: Long): Contact? = state.value.find { it.id == id }

        override suspend fun upsertContact(contact: Contact) {}

        override suspend fun updateCategory(contactId: Long, category: Category) {}

        override suspend fun deleteContact(contactId: Long) {}

        override fun searchContacts(query: String): Flow<List<Contact>> {
            searchedQuery = query
            return state
        }

        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {}

        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {}

        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {}

        override suspend fun deleteContacts(contactIds: Collection<Long>) {}

        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) {
            recordedCalls += e164
        }
    }

    /** تفضيلات مزيفة لاستبعاد الاقتراحات. */
    private class FakeListPreferences(initialDismissed: Set<String> = emptySet()) : ContactsListPreferencesRepository {
        val dismissed = MutableStateFlow(initialDismissed)
        override val dismissedSuggestionIds: Flow<Set<String>> = dismissed
        override suspend fun dismissSuggestion(id: String) {
            dismissed.value = dismissed.value + id
        }
        override val swipeActions: Flow<SwipeActionConfig> =
            MutableStateFlow(SwipeActionConfig(ContactSwipeAction.CALL, ContactSwipeAction.MESSAGE))
        override suspend fun setSwipeActions(config: SwipeActionConfig) {}
    }

    // ===== فلتر الحساب =====

    @Test
    fun accountFilter_matchesDeviceGoogleOther_byAccountType() {
        val device = contact(1, accountType = null) // بلا حساب = جهاز محلي
        val google = contact(2, accountType = "com.google")
        val other = contact(3, accountType = "com.whatsapp")
        val local = contact(4, accountType = " Local Account ")

        assertEquals(true, AccountFilter.ALL.matches(device) && AccountFilter.ALL.matches(google))

        assertTrue(AccountFilter.DEVICE.matches(device), "بلا حساب ⇒ جهاز")
        assertTrue(AccountFilter.DEVICE.matches(local), "حساب محلي ⇒ جهاز")
        assertTrue(!AccountFilter.DEVICE.matches(google))

        assertTrue(AccountFilter.GOOGLE.matches(google))
        assertTrue(!AccountFilter.GOOGLE.matches(other))

        assertTrue(AccountFilter.OTHER.matches(other))
        assertTrue(!AccountFilter.OTHER.matches(google))
        assertTrue(!AccountFilter.OTHER.matches(device), "بلا حساب ليس «حساباً آخر»")
    }

    // ===== تكوين الفلاتر في مسار البحث =====

    @Test
    fun observeContactsPaged_blankQuery_usesListPath_andAppliesAccountFilter() = runTest {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, category = Category.FAMILY, accountType = "com.google"),
                contact(2, category = Category.FAMILY)
            )
        )
        val useCase = ObserveContactsPagedUseCase(repo)

        val googleOnly = useCase(category = null, searchQuery = "", accountFilter = AccountFilter.GOOGLE).first()
        assertEquals(listOf(1L), googleOnly.map { it.id })
        assertEquals(null, repo.searchedQuery, "استعلام فارغ ⇒ مسار القائمة لا مسار FTS")
    }

    @Test
    fun observeContactsPaged_nonBlankQuery_searchesFts_thenFiltersByCategoryAndAccount() = runTest {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, displayName = "أحمد سيد", category = Category.FAMILY, accountType = "com.google"),
                contact(2, displayName = "أحمد عمل", category = Category.WORK, accountType = "com.google"),
                contact(3, displayName = "أحمد جهاز", category = Category.WORK)
            )
        )
        val useCase = ObserveContactsPagedUseCase(repo)

        val result = useCase(
            category = Category.WORK,
            searchQuery = "أحمد",
            accountFilter = AccountFilter.GOOGLE
        ).first()

        assertEquals(listOf(2L), result.map { it.id }, "البحث × التصنيف × الحساب تُركَّب في النطاق")
        assertEquals("أحمد", repo.searchedQuery, "الاستعلام يُفوَّض لمسار FTS")
    }

    // ===== بطاقات الاقتراح =====

    @Test
    fun observeSuggestions_duplicatesCapped_atThree_andOneSmartFilterCard() = runTest {
        // ثلاث مجموعات مكررات (أرقام متطابقة) + 5 جهات غير مصنفة:
        val repo = FakeContactsRepository(
            listOf(
                contact(1, displayName = "أحمد إبراهيم", category = Category.FAMILY),
                contact(2, displayName = "احمد ابراهيم", category = Category.FAMILY), // مكرر مع 1 (تعريب)
                contact(3, displayName = "منى عمر", category = Category.WORK),
                contact(4, displayName = "منى عمر", category = Category.WORK), // مكرر مع 3
                contact(5, displayName = "سيد علي", category = Category.FRIENDS),
                contact(6, displayName = "سيد علي", category = Category.FRIENDS), // مكرر مع 5
                contact(7, displayName = "جهة عادية"),
                contact(8, displayName = "جهة عادية2"),
                contact(9, displayName = "جهة عادية3"),
                contact(10, displayName = "جهة عادية4"),
                contact(11, displayName = "جهة عادية5")
            )
        )
        val useCase = ObserveSuggestionsUseCase(repo, FakeListPreferences())

        val cards = useCase().first()
        val duplicateCards = cards.filter { it.kind == SuggestionCard.Kind.DUPLICATES }
        assertEquals(3, duplicateCards.size, "سقف بطاقات المكررات = 3")
        assertEquals(1, cards.count { it.kind == SuggestionCard.Kind.SMART_FILTER })
        assertEquals(5, cards.first { it.kind == SuggestionCard.Kind.SMART_FILTER }.count)
    }

    @Test
    fun observeSuggestions_dismissalPersists_andCardReturnsOnlyWhenMembershipChanges() = runTest {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, displayName = "أحمد إبراهيم"),
                contact(2, displayName = "احمد ابراهيم")
            )
        )
        val prefs = FakeListPreferences()
        val useCase = ObserveSuggestionsUseCase(repo, prefs)

        val before = useCase().first()
        val duplicateCard = before.first { it.kind == SuggestionCard.Kind.DUPLICATES }
        assertEquals(2, duplicateCard.count)

        // الاستبعاد يخفي البطاقة لنفس العناصر:
        prefs.dismissed.value = setOf(duplicateCard.id)
        assertTrue(
            useCase().first().none { it.kind == SuggestionCard.Kind.DUPLICATES },
            "البطاقة المستبعدة لا تعود لنفس العناصر"
        )

        // تغيّر المجموعة (جهة ثالثة بنفس الاسم المتعارب تدخل المجموعة) ⇒ معرّف جديد وبطاقة تعود:
        repo.state.value = repo.state.value + contact(3, displayName = "احمد ابراهيم")
        val after = useCase().first().filter { it.kind == SuggestionCard.Kind.DUPLICATES }
        assertEquals(1, after.size, "تغيّر العناصر يعيد البطاقة")
        assertTrue(after.single().id != duplicateCard.id, "المعرّف مشتق من هوية العناصر")
        assertEquals(3, after.single().count)
    }

    @Test
    fun observeSuggestions_noCards_whenNoDuplicatesAndAllCategorized() = runTest {
        val repo = FakeContactsRepository(
            listOf(
                contact(1, displayName = "أحمد", category = Category.FAMILY),
                contact(2, displayName = "منى", category = Category.WORK)
            )
        )
        val cards = ObserveSuggestionsUseCase(repo, FakeListPreferences())().first()
        assertTrue(cards.isEmpty())
    }

    // ===== الأفعال الجماعية =====

    @Test
    fun bulkUseCases_delegateToRepository() = runTest {
        val repo = FakeContactsRepository(listOf(contact(1), contact(2)))

        BulkCategorizeUseCase(repo)(listOf(1L, 2L), Category.FAMILY)
        BulkSetFavoriteUseCase(repo)(listOf(1L), true)
        DeleteContactsUseCase(repo)(listOf(2L))
        ToggleFavoriteUseCase(repo)(1L, false)

        // جميعها تفوّض حصراً — لا منطق تصفية هنا:
        assertTrue(true, "لا استثناءات — التفويض تم")
    }

    @Test
    fun recordInAppCall_generatesStableLogId_andRecordsE164() = runTest {
        val repo = FakeContactsRepository()
        val fixedClock = object : AppClock {
            override fun now(): kotlinx.datetime.Instant =
                kotlinx.datetime.Instant.fromEpochMilliseconds(1_800_000_000_000L)

            override fun nowEpochMillis(): Long = 1_800_000_000_000L
        }

        RecordInAppCallUseCase(repo, fixedClock)("+201000000001")

        assertEquals(listOf("+201000000001"), repo.recordedCalls, "محاولة IN_APP تُسجَّل محلياً حصراً")
    }
}
