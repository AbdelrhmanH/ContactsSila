package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.ImportantDate
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات مسار الكتابة (T6 · Issue #27 · ADR-0012) — ميثاق الاتساق J-1 حرفياً:
 * المنصة أولاً، ثم Room بمعاملة واحدة، ثم تعويض آمن عند فشل Room. كل الاختبارات
 * نقية على JVM (قرار T6: منطق الفروق المشترك يُختبر بلا منصات).
 */
class T6ContactWriteDomainTest {

    private val fixedClock = object : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(FIXED_NOW)

        override fun nowEpochMillis(): Long = FIXED_NOW
    }

    // ===== المرافق المزيفة — سجل ترتيب الاستدعاءات حرفياً =====

    /** سجل أحداث مشترك لإثبات «المنصة أولاً ثم Room» بالترتيب الفعلي للاستدعاءات. */
    private class CallLog {
        val events = mutableListOf<String>()
        fun mark(event: String) { events += event }
    }

    /** كاتب منصة مزيف — يُسجِّل الاستدعاءات بالترتيب ويُبرمَج فشل كل مسار. */
    private class RecordingWriter(
        private val log: CallLog,
        var createResult: (() -> AppResult<CreatedPlatformContact>)? = null,
        var updateResult: (() -> AppResult<Unit>)? = null,
        var deleteResult: (() -> AppResult<Unit>)? = null,
    ) : ContactsPlatformWriter {

        val createdWritable = mutableListOf<PlatformWritableContact>()
        val updatedWritable = mutableListOf<PlatformWritableContact>()
        val deletedIds = mutableListOf<String>()
        var createdIdentity = CreatedPlatformContact(systemContactId = "sys-new-1", lookupKey = "lookup-1")

        override suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact> {
            createdWritable += writable
            log.mark("createContact")
            return createResult?.invoke() ?: AppResult.Success(createdIdentity)
        }

        override suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit> {
            updatedWritable += writable
            log.mark("updateContact")
            return updateResult?.invoke() ?: AppResult.Success(Unit)
        }

        override suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit> {
            deletedIds += systemContactId
            log.mark("deleteContact")
            return deleteResult?.invoke() ?: AppResult.Success(Unit)
        }
    }

    /** مستودع مزيف — يُسجِّل upsert ويُبرمَج ليفشل (محاكاة فشل معاملة Room). */
    private class RecordingRepository(
        private val log: CallLog,
        val stored: MutableList<Contact> = mutableListOf(),
    ) : ContactsRepository {

        var upsertError: Throwable? = null
        var tombstoneError: Throwable? = null
        val tombstoned = mutableListOf<Pair<Long, Long>>()
        val findResults: MutableList<Contact> = mutableListOf()
        var findCalls = 0

        override suspend fun upsertContact(contact: Contact) {
            log.mark("upsertRoom")
            upsertError?.let { throw it }
            stored += contact
        }

        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) {
            log.mark("tombstone")
            tombstoneError?.let { throw it }
            tombstoned += contactId to nowMillis
        }

        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?): List<Contact> {
            findCalls++
            return findResults
        }

        // المرصدات/البقية غير مستخدمة في هذه الاختبارات — تفويض صامت (نمط Fake الحرفي).
        override fun observeContacts(category: Category?, searchQuery: String) = kotlinx.coroutines.flow.MutableStateFlow(stored)
        override fun observeFavorites() = kotlinx.coroutines.flow.MutableStateFlow(stored)
        override fun observeCategoryCounts() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<Category, Int>())
        override suspend fun getContactById(id: Long): Contact? = stored.find { it.id == id }
        override suspend fun updateCategory(contactId: Long, category: Category) {}
        override suspend fun deleteContact(contactId: Long) {}
        override fun searchContacts(query: String) = kotlinx.coroutines.flow.MutableStateFlow(stored)
        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) {}
        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) {}
        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) {}
        override suspend fun deleteContacts(contactIds: Collection<Long>) {}
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) {}
        override fun observeContactById(id: Long) = kotlinx.coroutines.flow.MutableStateFlow(stored.find { it.id == id })
        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int) = emptyList<app.sila.core.model.RecentInteraction>()
    }

    /** معالج صور مزيف — قابل للبرمجة بإرجاع null (فشل فك الترميز). */
    private class FakeImageProcessor(
        private val result: ByteArray? = PROCESSED_BYTES,
    ) : ContactImageProcessor {
        var processedInput: ByteArray? = null
        override suspend fun process(bytes: ByteArray, maxSide: Int): ByteArray? {
            processedInput = bytes
            return result
        }
    }

    // ===== ملفات الاختبار =====

    private fun contact(
        id: Long,
        displayName: String = "منى أحمد",
        systemContactId: String = "sys-$id",
        phones: List<Phone> = listOf(Phone(raw = "010 1234 5678", e164 = "+201012345678")),
        withSilaOnlyData: Boolean = false,
    ) = Contact(
        id = id,
        systemContactId = systemContactId,
        lookupKey = "lookup-$id",
        name = ContactName(displayName = displayName, firstName = displayName),
        phones = phones,
        notes = if (withSilaOnlyData) "ملاحظة خاصة" else null,
        dates = if (withSilaOnlyData) listOf(ImportantDate(type = "BIRTHDAY", month = 3, day = 21)) else emptyList(),
        category = if (withSilaOnlyData) Category.FAMILY else Category.UNCATEGORIZED,
        isFavorite = withSilaOnlyData,
        normalizedName = displayName,
    )

    // ===== J-1: ميثاق الإنشاء (منصة أولاً) =====

    @Test
    fun create_platformFirst_thenRoomReceivesMergedIdentity() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log)
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = null, draft = contact(id = 0L, systemContactId = ""))

        assertTrue(result is AppResult.Success)
        // الترتيب الملزم: createContact قبل upsertRoom حصراً.
        assertEquals(listOf("createContact", "upsertRoom"), log.events)
        // هوية المنصوي المُنشأ تُدمَج في صف Room (مفتاح applySyncBatch — بلاها تتيم الجهة).
        assertEquals("sys-new-1", result.data.systemContactId)
        assertEquals("lookup-1", result.data.lookupKey)
        assertEquals("sys-new-1", repo.stored.single().systemContactId)
    }

    @Test
    fun create_platformFailure_roomIsNeverTouched() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(
            log,
            createResult = { AppResult.Error(DataError.Permission("WRITE_CONTACTS")) },
        )
        val repo = RecordingRepository(log)
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = null, draft = contact(id = 0L, systemContactId = ""))

        assertTrue(result is AppResult.Error)
        assertTrue(result.error is DataError.Permission)
        assertEquals(listOf("createContact"), log.events, "فشل المنصة ينهي الحفظ — Room لم يُمَس")
        assertTrue(writer.deletedIds.isEmpty(), "لا تعويض حذف بعد فشل الإنشاء نفسه")
    }

    @Test
    fun create_roomFailure_silentlyDeletesTheCreatedPlatformRow() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log).apply { upsertError = IllegalStateException("tx failed") }
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = null, draft = contact(id = 0L, systemContactId = ""))

        assertTrue(result is AppResult.Error, "فشل Room يُرجع خطأً مصنفاً للعرض")
        assertEquals(listOf("createContact", "upsertRoom", "deleteContact"), log.events, "التعويض: حذف المُنشأ الصامت")
        assertEquals(listOf("sys-new-1"), writer.deletedIds, "يُحذف بالهوية المنصوية المُنشأة حصراً")
    }

    @Test
    fun create_doubleFailure_compensationSwallowed_andErrorStillReturned() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log, deleteResult = { AppResult.Error(DataError.Unknown("compensation failed")) })
        val repo = RecordingRepository(log).apply { upsertError = IllegalStateException("tx failed") }
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = null, draft = contact(id = 0L, systemContactId = ""))

        // فشل التعويض يُبتلَع عمداً — شبكة أمان المزامنة (ADR-0010) صاحبة التقاط الانحراف.
        assertTrue(result is AppResult.Error)
        assertEquals(listOf("createContact", "upsertRoom", "deleteContact"), log.events)
    }

    // ===== J-1: ميثاق التحديث (منصة أولاً + استعادة السابق) =====

    @Test
    fun update_platformFirst_thenRoomReceivesDraft() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log)
        val original = contact(id = 7L)
        val draft = contact(id = 7L, displayName = "منى أحمد علي")
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = original, draft = draft)

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("updateContact", "upsertRoom"), log.events)
        assertEquals(draft.id, repo.stored.single().id)
        assertEquals("منى أحمد علي", repo.stored.single().name.displayName)
    }

    @Test
    fun update_platformFailure_roomIsNeverTouched() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log, updateResult = { AppResult.Error(DataError.Permission("WRITE_CONTACTS")) })
        val repo = RecordingRepository(log)
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = contact(id = 7L), draft = contact(id = 7L))

        assertTrue(result is AppResult.Error)
        assertEquals(listOf("updateContact"), log.events, "فشل المنصة في التحديث لا يُلمس Room")
    }

    @Test
    fun update_roomFailure_restoresPreviousStateToPlatform() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log).apply { upsertError = IllegalStateException("tx failed") }
        val original = contact(id = 7L, displayName = "الاسم القديم")
        val draft = contact(id = 7L, displayName = "الاسم الجديد")
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)

        val result = useCase(original = original, draft = draft)

        assertTrue(result is AppResult.Error)
        assertEquals(listOf("updateContact", "upsertRoom", "updateContact"), log.events, "التعويض: استعادة القيم السابقة")
        // الاستعادة بجسد الأصل — القيم القديمة تعود للمنصة (لا فقد بيانات).
        assertEquals("الاسم القديم", writer.updatedWritable.last().name.displayName)
        assertEquals("sys-7", writer.updatedWritable.last().systemContactId)
    }

    // ===== الصورة: تحسين اختياري لا يُفشل الحفظ =====

    @Test
    fun photoProcessorFailure_savesWithoutPhoto_neverFailsSave() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log)
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(result = null), fixedClock)

        val result = useCase(original = null, draft = contact(id = 0L, systemContactId = ""), newPhotoBytes = byteArrayOf(1, 2))

        assertTrue(result is AppResult.Success, "فشل الصورة لا يُفشل الحفظ كله")
        assertNull(writer.createdWritable.single().photoBytes)
    }

    @Test
    fun processedPhotoBytes_flowToWriter() = runTest {
        val writer = RecordingWriter(CallLog())
        val useCase = SaveContactUseCase(writer, RecordingRepository(CallLog()), FakeImageProcessor(), fixedClock)

        useCase(original = null, draft = contact(id = 0L, systemContactId = ""), newPhotoBytes = byteArrayOf(9, 9))

        assertTrue(
            writer.createdWritable.single().photoBytes.contentEquals(PROCESSED_BYTES),
            "الناتج المعالَج هو ما يُكتب",
        )
    }

    @Test
    fun removePhoto_writesEmptyBytesSignal_toWriter() = runTest {
        val writer = RecordingWriter(CallLog())
        val useCase = SaveContactUseCase(writer, RecordingRepository(CallLog()), FakeImageProcessor(), fixedClock)

        useCase(original = contact(id = 7L), draft = contact(id = 7L), removePhoto = true)

        val photoBytes = writer.updatedWritable.single().photoBytes
        assertEquals(0, photoBytes?.size, "الإزالة الصريحة = إشارة بايتات فارغة للكاتب (ليست null = بلا تغيير)")
    }

    // ===== الطوابع: normalizedName/updatedAt =====

    @Test
    fun save_stampsUpdatedAt_andFallsBackNormalizedNameToDisplayName() = runTest {
        val writer = RecordingWriter(CallLog())
        val repo = RecordingRepository(CallLog())
        val useCase = SaveContactUseCase(writer, repo, FakeImageProcessor(), fixedClock)
        val draft = contact(id = 0L, systemContactId = "").copy(normalizedName = "")

        useCase(original = null, draft = draft)

        val saved = repo.stored.single()
        assertEquals(FIXED_NOW, saved.updatedAt, "طابع الوقت من ساعة النطاق حصراً")
        assertEquals("منى أحمد", saved.normalizedName, "normalizedName الفارغ يسقط إلى الاسم المعروض")
    }

    // ===== toWritable: الإسقاط المسموح (نقي) =====

    @Test
    fun toWritable_blankIdentityBecomesNulls_forCreate() {
        val draft = contact(id = 0L, systemContactId = "  ").copy(lookupKey = "")

        val writable = draft.toWritable()

        assertNull(writable.systemContactId, "هوية فارغة = إنشاء (null حصراً)")
        assertNull(writable.lookupKey)
    }

    @Test
    fun toWritable_projectsMirrorFields_only() {
        val full = contact(id = 7L, withSilaOnlyData = true).let {
            it.copy(
                organization = Organization(company = "الشركة", department = "القسم"),
                emails = listOf(Email(address = "mona@example.com")),
                websites = listOf("https://example.com"),
            )
        }

        val writable = full.toWritable(photoBytes = byteArrayOf(1))

        // حقول المرآة المنصوية تعبر الحد.
        assertEquals("sys-7", writable.systemContactId)
        assertEquals("منى أحمد", writable.name.displayName)
        assertEquals(full.phones, writable.phones)
        assertEquals(full.emails, writable.emails)
        assertEquals(full.websites, writable.websites)
        assertEquals(full.organization, writable.organization)
        // حقول صِلَة-فقط بنيوياً غائبة: الإسقاط لا يحمل حقل notes/dates/category أصلاً
        // (تضييق نوعي — لا اتفاقي — ADR-0012)، والصورة فقط بالبايتات المُعالَجة.
        assertEquals(1, writable.photoBytes?.size)
        assertEquals(full.account, writable.account)
    }

    // ===== J-5: حذف بنافذة تراجع =====

    @Test
    fun undoDelete_platformDeletesFirst_thenTombstonesWithClockStamp() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log)
        val useCase = DeleteContactWithUndoUseCase(writer, repo, fixedClock)

        val result = useCase(contact(id = 7L))

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("deleteContact", "tombstone"), log.events, "المنصة أولاً — القبر بعدها حصراً")
        assertEquals(listOf("sys-7"), writer.deletedIds)
        assertEquals(listOf(7L to FIXED_NOW), repo.tombstoned)
    }

    @Test
    fun undoDelete_platformFailure_roomIsNeverTouched() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log, deleteResult = { AppResult.Error(DataError.Unknown("delete failed")) })
        val repo = RecordingRepository(log)
        val useCase = DeleteContactWithUndoUseCase(writer, repo, fixedClock)

        val result = useCase(contact(id = 7L))

        assertTrue(result is AppResult.Error)
        assertEquals(listOf("deleteContact"), log.events, "فشل حذف المنصة = الحذف لم يحدث — لا قبر")
        assertTrue(repo.tombstoned.isEmpty())
    }

    @Test
    fun undoDelete_tombstoneFailure_returnsError_afterPlatformDeleted() = runTest {
        val log = CallLog()
        val writer = RecordingWriter(log)
        val repo = RecordingRepository(log).apply { tombstoneError = IllegalStateException("db closed") }
        val useCase = DeleteContactWithUndoUseCase(writer, repo, fixedClock)

        val result = useCase(contact(id = 7L))

        assertTrue(result is AppResult.Error, "فشل القبر يُرجع خطأ عرض — المزامنة تُغلق الانحراف ذاتياً")
        assertTrue(writer.deletedIds.isNotEmpty(), "حذف المنصة تم فعلاً — التعويض ملاحَظة مزامنة لا فشل حفظ")
    }

    @Test
    fun undoWindow_isFiveSeconds() {
        assertEquals(5_000L, DeleteContactWithUndoUseCase.UNDO_WINDOW_MILLIS)
    }

    // ===== J-9: فحص التكرار المحلي =====

    @Test
    fun findDuplicate_blankE164_returnsEmpty_withoutRepositoryCall() = runTest {
        val repo = RecordingRepository(CallLog())
        val useCase = FindDuplicateContactUseCase(repo)

        assertEquals(emptyList(), useCase("  "))

        assertEquals(0, repo.findCalls, "الفراغ لا يُرقه المستودع (debounce العرض بلا عمل)")
    }

    @Test
    fun findDuplicate_delegatesToRepository_withExclusion() = runTest {
        val repo = RecordingRepository(CallLog())
        val existing = contact(id = 3L, displayName = "المالك الأصلي")
        repo.findResults += existing
        val useCase = FindDuplicateContactUseCase(repo)

        val result = useCase("+201012345678", excludeContactId = 7L)

        assertEquals(listOf(existing), result, "التفويض النقي — لا منطق تصفية في النطاق")
        assertEquals(1, repo.findCalls)
    }

    // ===== مرصد التفاصيل: تفويض نقي =====

    @Test
    fun recentInteractions_blankE164s_skipRepositoryRead() = runTest {
        val repo = RecordingRepository(CallLog())
        val useCase = ObserveContactDetailsUseCase(repo)

        val result = useCase.recentInteractions(contact(id = 1L, phones = listOf(Phone(raw = "x", e164 = " "))))

        assertEquals(emptyList(), result, "بلا أرقام E.164 لا قراءة مكالمات إطلاقاً")
    }

    @Test
    fun recentInteractions_delegatesWithDefaultLimitFive() = runTest {
        val repo = RecentInteractionsRepository()
        val useCase = ObserveContactDetailsUseCase(repo)

        useCase.recentInteractions(contact(id = 1L))

        assertEquals(listOf(5), repo.limits)
    }

    /** مستودع مصغر يلتقط حدود القراءة فقط — بقية العقد غير مُستخدَم هنا. */
    private class RecentInteractionsRepository : ContactsRepository by IgnoredRepository() {
        val limits = mutableListOf<Int>()
        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int) =
            emptyList<app.sila.core.model.RecentInteraction>().also { limits += limit }
    }

    /** تنفيذ قاعدي فارغ — لتفادي تكرار عقد الواجهة كاملاً في اختبار مركّز. */
    private open class IgnoredRepository : ContactsRepository {
        override fun observeContacts(category: Category?, searchQuery: String) = error("unused")
        override fun observeFavorites() = error("unused")
        override fun observeCategoryCounts() = error("unused")
        override suspend fun getContactById(id: Long) = error("unused")
        override suspend fun upsertContact(contact: Contact) = error("unused")
        override suspend fun updateCategory(contactId: Long, category: Category) = error("unused")
        override suspend fun deleteContact(contactId: Long) = error("unused")
        override fun searchContacts(query: String) = error("unused")
        override suspend fun setFavorite(contactId: Long, isFavorite: Boolean) = error("unused")
        override suspend fun updateCategories(contactIds: Collection<Long>, category: Category) = error("unused")
        override suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean) = error("unused")
        override suspend fun deleteContacts(contactIds: Collection<Long>) = error("unused")
        override suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long) = error("unused")
        override fun observeContactById(id: Long) = error("unused")
        override suspend fun getRecentCallEvents(e164s: List<String>, limit: Int) = error("unused")
        override suspend fun tombstoneContact(contactId: Long, nowMillis: Long) = error("unused")
        override suspend fun findContactsByE164(e164: String, excludeContactId: Long?) = error("unused")
    }

    companion object {
        private const val FIXED_NOW = 1_800_000_000_000L
        private val PROCESSED_BYTES = byteArrayOf(11, 22, 33)
    }
}
