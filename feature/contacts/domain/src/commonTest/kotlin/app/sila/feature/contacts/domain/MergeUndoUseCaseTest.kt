package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * عقود J-1 لحالتي الدمج والتراجع (T7 · Issue #28): المنصة أولاً بلا استثناء، هجرة الهوية
 * الموحدة، قفل الاسترجاع لمرة-واحدة، وإعادة تعيين هويات iOS المُعاد إنشاؤها (agy F3a).
 */
class MergeUndoUseCaseTest {

    private class FixedClock(var now: Long = 1_000L) : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(now)

        override fun nowEpochMillis(): Long = now
    }

    /** كاتب منصوي مسجِّل — يثبت الترتيب (المنصة قبل Room) ويسمح بحقن فشل. */
    private class RecordingMergeWriter : ContactsMergeWriter {
        val calls = mutableListOf<String>()
        var mergeResult: AppResult<PlatformMergeResult> =
            AppResult.Success(PlatformMergeResult("sys_unified", "lk_unified"))
        var unmergeResult: AppResult<PlatformUnmergeResult> =
            AppResult.Success(PlatformUnmergeResult(emptyMap()))

        override suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult> {
            calls += "platform-merge"
            return mergeResult
        }

        override suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult> {
            calls += "platform-unmerge"
            return unmergeResult
        }
    }

    /** مخزن دمج زائف بأمانة العقد: تسجيل الترتيب + القفل لمرة-واحدة + تطبيق remap. */
    private class FakeMergeStore : ContactsMergeStore {
        val calls = mutableListOf<String>()
        var builtContent: MergeSnapshotContent? = null
        var mergeError: Throwable? = null
        var snapshotIdCounter = 0L
        val snapshots = mutableMapOf<Long, MergeSnapshotContent>()
        val activeSnapshots = mutableSetOf<Long>()

        override suspend fun mergeInRoom(
            survivorId: Long,
            mergedContact: Contact,
            absorbedIds: List<Long>,
            groupId: String,
            expiresAt: Long,
            nowMillis: Long,
            buildContent: (DiscoveredReferences) -> MergeSnapshotContent,
        ): MergeRoomCommit {
            calls += "room-merge"
            mergeError?.let { throw it }
            val id = ++snapshotIdCounter
            val content = buildContent(DiscoveredReferences(listOf("log_1"), listOf(9L to 2L)))
            builtContent = content
            snapshots[id] = content
            activeSnapshots += id
            return MergeRoomCommit(
                snapshotId = id,
                groupId = groupId,
                callEventRepoints = content.callEventRepoints,
                parentLinkRepoints = content.parentLinkRepoints,
            )
        }

        override suspend fun undoInRoom(
            snapshotId: Long,
            nowMillis: Long,
            remap: (MergeSnapshotContent) -> MergeSnapshotContent,
        ): UndoRoomOutcome {
            calls += "room-undo"
            if (snapshotId !in activeSnapshots) return UndoRoomOutcome.SnapshotNotActive
            activeSnapshots -= snapshotId
            // كالواجهة الفعلية: المحتوى المُعاد تعيينه هو الحقيقة المكتوبة بعد الاسترجاع
            val restored = remap(snapshots.getValue(snapshotId))
            snapshots[snapshotId] = restored
            return UndoRoomOutcome.Restored(restored)
        }

        override suspend fun snapshotContent(snapshotId: Long): MergeSnapshotContent? = snapshots[snapshotId]
        override suspend fun activeSnapshotsForSweep(olderThan: Long): List<SweepableSnapshot> = emptyList()
        override suspend fun markSnapshotExpired(snapshotId: Long) = Unit
        override suspend fun purgeSnapshotsOlderThan(olderThan: Long) = Unit
        override fun observeDismissedPairKeys() = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        override suspend fun dismissPair(groupKey: String, reason: String, nowMillis: Long) = Unit
        override fun observeFeedback() = kotlinx.coroutines.flow.MutableStateFlow(emptyList<SmartFilterWeight>())
        override suspend fun recordFeedback(token: String, category: Category, accepted: Boolean) = Unit
    }

    private fun contact(id: Long, createdAt: Long, systemId: String = "sys_$id") = Contact(
        id = id,
        systemContactId = systemId,
        name = ContactName(displayName = "عضو $id"),
        phones = listOf(Phone(raw = "+20100000000$id", e164 = "+20100000000$id")),
        createdAt = createdAt,
    )

    private fun twoMembers() = listOf(contact(1, createdAt = 100L), contact(2, createdAt = 200L))

    // ——— الدمج ———

    @Test
    fun merge_platformFirst_failureLeavesRoomUntouched() = runTest {
        val writer = RecordingMergeWriter().apply {
            mergeResult = AppResult.Error(DataError.Permission(permissionName = "WRITE_CONTACTS"))
        }
        val store = FakeMergeStore()
        val result = MergeContactsUseCase(writer, store, FixedClock())(twoMembers())

        assertTrue(result is AppResult.Error)
        assertEquals(listOf("platform-merge"), writer.calls, "المنصة أولاً")
        assertEquals(emptyList(), store.calls, "فشل المنصة لا يمس Room إطلاقاً (J-1)")
    }

    @Test
    fun merge_success_migratesSurvivorIdentity_andBuildsPayloadWithVCardsAndReferences() = runTest {
        val writer = RecordingMergeWriter()
        val store = FakeMergeStore()
        val result = MergeContactsUseCase(writer, store, FixedClock())(twoMembers())

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("platform-merge", "room-merge"), writer.calls + store.calls, "الترتيب J-1")
        val content = store.builtContent!!
        assertEquals(listOf("sys_1", "sys_2"), content.members.map { it.systemContactId }.sorted())
        assertEquals(listOf("sys_2"), content.absorbedSystemIds)
        assertTrue(content.absorbedVCards.containsKey("sys_2"), "vCard الممتص داخل اللقطة (agy F3a — بلا ملفات)")
        assertEquals(listOf("log_1"), content.callEventRepoints.map { it.callLogId })
        assertEquals(listOf(9L to 2L), content.parentLinkRepoints.map { it.childContactId to it.oldParentId })
    }

    @Test
    fun merge_roomFailure_afterPlatformSuccess_isHonestError_notSilentSuccess() = runTest {
        val writer = RecordingMergeWriter()
        val store = FakeMergeStore().apply { mergeError = IllegalStateException("disk full") }
        val result = MergeContactsUseCase(writer, store, FixedClock())(twoMembers())

        assertTrue(result is AppResult.Error, "فشل Room = خطأ صادق (لا نجاح زائف — J-1 §4)")
        assertEquals(listOf("platform-merge", "room-merge"), writer.calls + store.calls)
    }

    // ——— التراجع ———

    @Test
    fun undo_missingSnapshot_isNotFound_andWriterUntouched() = runTest {
        val writer = RecordingMergeWriter()
        val store = FakeMergeStore()
        val result = UndoMergeUseCase(writer, store, FixedClock())(999L)

        assertTrue(result is AppResult.Error && result.error is DataError.NotFound)
        assertEquals(emptyList(), writer.calls)
    }

    @Test
    fun undo_platformFirst_thenRoomRestore_appliesIdentityRemap_andLatchIsOneTime() = runTest {
        val writer = RecordingMergeWriter().apply {
            unmergeResult = AppResult.Success(PlatformUnmergeResult(mapOf("sys_2" to "sys_new_ios")))
        }
        val store = FakeMergeStore()
        val useCase = UndoMergeUseCase(writer, store, FixedClock())

        // لقطة نشطة من دمج سابق
        MergeContactsUseCase(RecordingMergeWriter(), store, FixedClock())(twoMembers())

        val first = useCase(1L)
        assertTrue(first is AppResult.Success)
        assertEquals("platform-unmerge", writer.calls.last(), "المنصة أولاً في التراجع أيضاً")
        assertEquals("room-undo", store.calls.last())
        val restoredContent = store.snapshots.getValue(1L)
        assertTrue(restoredContent.members.any { it.systemContactId == "sys_new_ios" }, "هوية iOS المُعاد إنشاؤها تُهاجر داخل الاسترجاع (agy F3a)")

        val second = useCase(1L)
        assertTrue(second is AppResult.Error, "القفل لمرة-واحدة: النداء الثاني مرفوض")
    }
}
