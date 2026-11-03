package app.sila.feature.contacts.presentation

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.RecentInteraction
import app.sila.core.model.Relation
import app.sila.feature.contacts.domain.ConnectedApp
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import app.sila.feature.contacts.domain.ContactsPlatformWriter
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.CreatedPlatformContact
import app.sila.feature.contacts.domain.DeleteContactWithUndoUseCase
import app.sila.feature.contacts.domain.ObserveContactDetailsUseCase
import app.sila.feature.contacts.domain.PlatformWritableContact
import app.sila.feature.contacts.domain.RecordInAppCallUseCase
import app.sila.feature.contacts.domain.ToggleFavoriteUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات ViewModel التفاصيل — الترتيب الجديد الذي أدخله T6 حصراً (Issue #27):
 * **نافذة التراجع J-5** (بدايتها/تراجعها بلا كتابة/انصرافها بالحذف النهائي) وحساب
 * أيام آخر اتصال أرحام — بنمط الأسلاك القياسي: Main مزيف بزمن افتراضي مشترك،
 * و`runCurrent`/`advanceTimeBy` للتحكم الدقيق بالزمن (الانتظار الشامل `advanceUntilIdle`
 * يُصرف نافذة الـ 5 ثوانٍ كاملة — لا يصلح بعد `RequestDelete`).
 * (سابقة الوحدة: T3/T5 لم تُرفق اختبارات VM؛ هذه الاختبارات لأصغر سلوك جديد فحسب.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== المزايف — سجل حرفي لما كُتب ومتى =====

    private class FakeContactsRepository(initial: List<Contact> = emptyList()) : ContactsRepository {
        val state = MutableStateFlow(initial)
        val tombstonedIds = mutableListOf<Long>()
        val recordedCallE164s = mutableListOf<String>()

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
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) {
            recordedCallE164s += e164
        }
        override fun observeContactById(id: Long): Flow<Contact?> =
            MutableStateFlow(state.value.find { it.id == id })
        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction> = emptyList()
        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) {
            tombstonedIds += contactId
        }
        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> = emptyList()
    }

    private class RecordingWriter : ContactsPlatformWriter {
        val deletedIds = mutableListOf<String>()
        override suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact> =
            AppResult.Success(CreatedPlatformContact(systemContactId = "sys-1", lookupKey = "lookup-1"))

        override suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit> {
            deletedIds += systemContactId
            return AppResult.Success(Unit)
        }
    }

    private fun contact(id: Long = 7L) = Contact(
        id = id,
        systemContactId = "sys-$id",
        lookupKey = "lookup-$id",
        name = ContactName(displayName = "منى أحمد", firstName = "منى"),
        phones = listOf(Phone(raw = "010 1234 5678", e164 = "+201012345678", isPrimary = true)),
        normalizedName = "منى أحمد",
    )

    private val fixedClock = object : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(FIXED_NOW)

        override fun nowEpochMillis(): Long = FIXED_NOW
    }

    private fun createViewModel(repo: FakeContactsRepository, writer: RecordingWriter) = ContactDetailsViewModel(
        contactId = 7L,
        observeDetails = ObserveContactDetailsUseCase(repo),
        deleteWithUndo = DeleteContactWithUndoUseCase(writer, repo, fixedClock),
        toggleFavorite = ToggleFavoriteUseCase(repo),
        changeCategory = UpdateContactCategoryUseCase(repo),
        recordInAppCall = RecordInAppCallUseCase(repo, fixedClock),
        connectedAppsDetector = object : ConnectedAppsDetector {
            override suspend fun detect(): List<ConnectedApp> = emptyList()
        },
        appClock = fixedClock,
    )

    /** تجهيز الشاشة: جمع الحالة والأثر بالخلفية ثم دورة زمنية واحدة. */
    private fun TestScope.setupScreen(
        vm: ContactDetailsViewModel,
        effects: MutableList<ContactDetailsEffect>,
    ) {
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        runCurrent()
    }

    /** دورة معالجة الزمن الحالي دون تقدّمه — نافذة التراجع لا تنصرف بها. */
    private fun TestScope.processNow() {
        runCurrent()
        runCurrent()
    }

    // ===== J-5: نافذة التراجع =====

    @Test
    fun requestDelete_showsUndoWindow_withoutWritingAnything() = runTest(dispatcher) {
        val writer = RecordingWriter()
        val repo = FakeContactsRepository(listOf(contact()))
        val vm = createViewModel(repo, writer)
        setupScreen(vm, mutableListOf())

        vm.onIntent(ContactDetailsIntent.RequestDelete)
        processNow()

        assertTrue(vm.state.value.isUndoWindowActive, "النافذة نشطة والشريط ظاهر")
        assertTrue(writer.deletedIds.isEmpty(), "بداية النافذة لا تكتب في المنصة")
        assertTrue(repo.tombstonedIds.isEmpty(), "بداية النافذة لا تكتب في Room")
    }

    @Test
    fun requestDelete_thenUndo_writesNothingAnywhere() = runTest(dispatcher) {
        val writer = RecordingWriter()
        val repo = FakeContactsRepository(listOf(contact()))
        val vm = createViewModel(repo, writer)
        setupScreen(vm, mutableListOf())

        vm.onIntent(ContactDetailsIntent.RequestDelete)
        processNow()
        vm.onIntent(ContactDetailsIntent.UndoDelete)
        processNow()

        assertFalse(vm.state.value.isUndoWindowActive, "التراجع يغلق النافذة")
        assertTrue(writer.deletedIds.isEmpty(), "التراجع لا يكتب في المنصة")
        assertTrue(repo.tombstonedIds.isEmpty(), "التراجع لا يكتب في Room")
        assertFalse(vm.state.value.isNotFound, "الجهة باقية بعد التراجع")

        // حتى بعد مرور ما يفوق النافذة بلا كتابة سابقة — لا حذف متأخر يظهر:
        advanceTimeBy(10_000)
        processNow()
        assertTrue(writer.deletedIds.isEmpty() && repo.tombstonedIds.isEmpty())
    }

    @Test
    fun requestDelete_windowExpiry_executesFinalDelete_andNavigatesBack() = runTest(dispatcher) {
        val writer = RecordingWriter()
        val repo = FakeContactsRepository(listOf(contact()))
        val vm = createViewModel(repo, writer)
        val effects = mutableListOf<ContactDetailsEffect>()
        setupScreen(vm, effects)

        vm.onIntent(ContactDetailsIntent.RequestDelete)
        processNow()
        assertTrue(writer.deletedIds.isEmpty(), "الحذف مؤجل حتى انصراف النافذة حصراً (J-5)")

        advanceTimeBy(DeleteContactWithUndoUseCase.UNDO_WINDOW_MILLIS)
        processNow()

        assertEquals(listOf("sys-7"), writer.deletedIds, "الانصراف ينفّذ الحذف المنصوي")
        assertEquals(listOf(7L), repo.tombstonedIds, "ثم قبر Room")
        assertTrue(effects.contains(ContactDetailsEffect.NavigateBack), "النجاح يعيد للقائمة")
        assertFalse(vm.state.value.isUndoWindowActive)
    }

    // ===== الاتصال: تسجيل المحاولة ثم فتح الهاتف =====

    @Test
    fun callNumber_recordsAttempt_andOpensDialer() = runTest(dispatcher) {
        val writer = RecordingWriter()
        val repo = FakeContactsRepository(listOf(contact()))
        val vm = createViewModel(repo, writer)
        val effects = mutableListOf<ContactDetailsEffect>()
        setupScreen(vm, effects)

        vm.onIntent(ContactDetailsIntent.CallNumber("+201012345678"))
        processNow()

        assertEquals(listOf("+201012345678"), repo.recordedCallE164s, "محاولة IN_APP تُسجَّل محلياً حصراً")
        assertTrue(effects.contains(ContactDetailsEffect.OpenDialer("+201012345678")))
    }

    // ===== أرحام: حساب الأيام من ساعة النطاق =====

    @Test
    fun arhamRow_frequencyAndDaysSinceLastContact_computedFromClock() = runTest(dispatcher) {
        val arhamContact = contact().copy(
            arham = ArhamInfo(
                relation = Relation.SISTER,
                frequency = ArhamFrequency.WEEKLY,
                lastContactedAt = FIXED_NOW - 3 * 86_400_000L,
            ),
        )
        val repo = FakeContactsRepository(listOf(arhamContact))
        val vm = createViewModel(repo, RecordingWriter())
        setupScreen(vm, mutableListOf())

        val state = vm.state.value
        assertEquals(ArhamFrequency.WEEKLY, state.arhamFrequency)
        assertEquals(3L, state.arhamDaysSinceLastContact, "أيام آخر اتصال تُحسب بساعة النطاق")
    }

    // ===== مشاركة vCard عبر الأثر =====

    @Test
    fun shareVCard_emitsEffectWithGeneratedText() = runTest(dispatcher) {
        val repo = FakeContactsRepository(listOf(contact()))
        val vm = createViewModel(repo, RecordingWriter())
        val effects = mutableListOf<ContactDetailsEffect>()
        setupScreen(vm, effects)

        vm.onIntent(ContactDetailsIntent.ShareVCard(7L))
        processNow()

        val share = effects.filterIsInstance<ContactDetailsEffect.ShareVCard>().single()
        assertTrue(share.vcardText.startsWith("BEGIN:VCARD"))
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L
    }
}
