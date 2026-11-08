package app.sila.feature.contacts.data

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.UndoRoomOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactsMergeStoreImplTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun coldStart_observeDismissedPairKeys_loadsFromDatabaseOnStart() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        // حفظ استبعاد مباشرة في قاعدة البيانات لمحاكاة وجود بيانات من جلسة سابقة
        db.dismissDuplicatePair("pair_1:pair_2", "NOT_DUPLICATES", 1000L)

        // إنشاء مخزن دمج جديد (محاكاة إقلاع بارد للعملية)
        val store = ContactsMergeStoreImpl(db)

        // القراءة الأولى دون أي كتابة سابقة في هذا الكائن يجب أن تُرجع الاستبعاد المحفوظ (agy P1#3)
        val keys = store.observeDismissedPairKeys().first()
        assertTrue(keys.contains("pair_1:pair_2"), "الإقلاع البارد يرى الاستبعادات المحفوظة عبر onStart")
    }

    @Test
    fun coldStart_observeFeedback_loadsFromDatabaseOnStart() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        // تسجيل تغذية راجعة مسبقة
        db.recordSmartFilterFeedback("دكتور", Category.SERVICES.name, accepted = true)

        // مخزن دمج جديد (إقلاع بارد)
        val store = ContactsMergeStoreImpl(db)

        // القراءة الأولى تُرجع الأوزان المحفوظة
        val feedback = store.observeFeedback().first()
        assertEquals(1, feedback.size, "الإقلاع البارد يرى التغذية الراجعة المحفوظة عبر onStart")
        val weight = feedback.first()
        assertEquals("دكتور", weight.token)
        assertEquals(Category.SERVICES, weight.category)
        assertEquals(1L, weight.acceptedCount)
    }

    @Test
    fun undoInRoom_capturesAbsorbedIdsBeforeRemap_restoringAllAbsorbedContacts() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val store = ContactsMergeStoreImpl(db)

        val contact1 = Contact(
            id = 10L,
            systemContactId = "sys_10",
            name = ContactName(displayName = "أحمد"),
            phones = listOf(Phone(raw = "+201000000010", e164 = "+201000000010")),
        )
        val contact2 = Contact(
            id = 20L,
            systemContactId = "sys_20",
            name = ContactName(displayName = "احمد"),
            phones = listOf(Phone(raw = "+201000000020", e164 = "+201000000020")),
        )

        db.upsertContact(contact1)
        db.upsertContact(contact2)

        val commit = store.mergeInRoom(
            survivorId = 10L,
            mergedContact = contact1,
            absorbedIds = listOf(20L),
            groupId = "group_1",
            expiresAt = 999_999L,
            nowMillis = 1000L,
        ) { refs ->
            app.sila.feature.contacts.domain.MergeSnapshotContent(
                members = listOf(contact1, contact2),
                absorbedSystemIds = listOf("sys_20"),
                callEventRepoints = emptyList(),
                parentLinkRepoints = emptyList(),
                absorbedVCards = emptyMap(),
                absorbedRawIds = emptyMap(),
            )
        }

        // الآن التراجع مع remap يغيّر systemContactId من sys_20 إلى sys_20_new (محاكاة هجرة iOS)
        val outcome = store.undoInRoom(
            snapshotId = commit.snapshotId,
            nowMillis = 2000L,
        ) { base ->
            base.copy(
                members = base.members.map { m ->
                    if (m.systemContactId == "sys_20") m.copy(systemContactId = "sys_20_new") else m
                },
            )
        }

        assertTrue(outcome is UndoRoomOutcome.Restored)
        assertEquals(2, outcome.content.members.size)
        // التحقق من أن جهة الاتصال الممتصة تم استرجاعها بنجاح في قاعدة البيانات
        val restoredAbsorbed = db.getContactById(20L)
        assertTrue(restoredAbsorbed != null, "الممتص تم استرجاعه لأن preRemapAbsorbedRowIds حماه (agy P1#2c)")
    }
}
