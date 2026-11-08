package app.sila.feature.contacts.data

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.Category
import app.sila.feature.contacts.domain.ContactsMergeStore
import app.sila.feature.contacts.domain.DiscoveredReferences
import app.sila.feature.contacts.domain.MergeRoomCommit
import app.sila.feature.contacts.domain.MergeSnapshotContent
import app.sila.feature.contacts.domain.SmartFilterWeight
import app.sila.feature.contacts.domain.SweepableSnapshot
import app.sila.feature.contacts.domain.UndoRoomOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * تنفيذ منفذ تخزين الدمج فوق واجهة `:core:database` الخارجية (T7 · Issue #28) — لا SQL
 * هنا ولا كيانات قاعدة تسرّب للنطاق (ADR-0007 · Rule 3): كل العمليات المركبة ذرّية داخل
 * الواجهة، والحمولة JSON معتمة بفكّ حصري [MergeSnapshotContentCodec].
 *
 * كاشا الاستبعاد والتعلّم يُحملان كسولاً عند أول قراءة (نفس انضباط كاش جهات الاتصال —
 * التهيئة الثقيلة لا تُكرَّر).
 */
class ContactsMergeStoreImpl(
    private val db: LocalSilaDatabase,
) : ContactsMergeStore {

    private var dismissalKeysLoaded = false
    private var feedbackLoaded = false

    private suspend fun ensureDismissalsLoaded() {
        if (!dismissalKeysLoaded) {
            db.loadDuplicateDismissalKeys()
            dismissalKeysLoaded = true
        }
    }

    private suspend fun ensureFeedbackLoaded() {
        if (!feedbackLoaded) {
            db.loadSmartFilterFeedback()
            feedbackLoaded = true
        }
    }

    override suspend fun mergeInRoom(
        survivorId: Long,
        mergedContact: app.sila.core.model.Contact,
        absorbedIds: List<Long>,
        groupId: String,
        expiresAt: Long,
        nowMillis: Long,
        buildContent: (DiscoveredReferences) -> MergeSnapshotContent,
    ): MergeRoomCommit {
        val result = db.mergeContactsInRoom(
            survivorId = survivorId,
            mergedContact = mergedContact,
            absorbedIds = absorbedIds,
            snapshotGroupId = groupId,
            snapshotExpiresAt = expiresAt,
            nowMillis = nowMillis,
        ) { references ->
            MergeSnapshotContentCodec.encode(
                buildContent(
                    DiscoveredReferences(
                        callLogIds = references.callLogIds,
                        parentLinks = references.parentLinks,
                    ),
                ),
            )
        }
        return MergeRoomCommit(
            snapshotId = result.snapshotId,
            groupId = groupId,
            callEventRepoints = result.references.callLogIds.map { callLogId ->
                MergeSnapshotContent.CallEventRepoint(callLogId, survivorId)
            },
            parentLinkRepoints = result.references.parentLinks.map { (child, oldParent) ->
                MergeSnapshotContent.ParentLinkRepoint(child, oldParent)
            },
        )
    }

    override suspend fun undoInRoom(
        snapshotId: Long,
        nowMillis: Long,
        remap: (MergeSnapshotContent) -> MergeSnapshotContent,
    ): UndoRoomOutcome {
        // remap يُطبَّق داخل معاملة الاسترجاع (فكّ الحمولة → تعيين الهويات → كتابة الأعضاء)
        var remapped: MergeSnapshotContent? = null
        val restored = db.undoMergeFromSnapshot(
            snapshotId = snapshotId,
            nowMillis = nowMillis,
            decodePayload = { json ->
                val base = MergeSnapshotContentCodec.decode(json)
                    ?: error("merge snapshot payload corrupted or unknown format")
                remap(base).also { remapped = it }
            },
            absorbedIdsOf = { content ->
                content.members
                    .filter { it.systemContactId in content.absorbedSystemIds.toSet() }
                    .map { it.id }
            },
            membersOf = { content -> content.members },
            callEventRepointsOf = { content ->
                content.callEventRepoints.map { it.callLogId to it.oldContactId }
            },
            parentLinkRepointsOf = { content ->
                content.parentLinkRepoints.map { it.childContactId to it.oldParentId }
            },
        )
        return if (restored && remapped != null) {
            UndoRoomOutcome.Restored(remapped!!)
        } else {
            UndoRoomOutcome.SnapshotNotActive
        }
    }

    override suspend fun snapshotContent(snapshotId: Long): MergeSnapshotContent? {
        val row = db.mergeSnapshotById(snapshotId) ?: return null
        if (row.status != "ACTIVE") return null
        return MergeSnapshotContentCodec.decode(row.payloadJson)
    }

    override suspend fun activeSnapshotsForSweep(olderThan: Long): List<SweepableSnapshot> =
        db.activeMergeSnapshotsForSweep(olderThan).mapNotNull { row ->
            MergeSnapshotContentCodec.decode(row.payloadJson)?.let { content ->
                SweepableSnapshot(
                    snapshotId = row.id,
                    groupId = row.groupId,
                    content = content,
                    createdAt = row.createdAt,
                )
            }
        }

    override suspend fun markSnapshotExpired(snapshotId: Long) {
        db.markMergeSnapshotExpired(snapshotId)
    }

    override suspend fun purgeSnapshotsOlderThan(olderThan: Long) {
        db.purgeMergeSnapshotsOlderThan(olderThan)
    }

    override fun observeDismissedPairKeys(): Flow<Set<String>> {
        return db.observeDuplicateDismissalKeys()
    }

    override suspend fun dismissPair(groupKey: String, reason: String, nowMillis: Long) {
        ensureDismissalsLoaded()
        db.dismissDuplicatePair(groupKey, reason, nowMillis)
    }

    override fun observeFeedback(): Flow<List<SmartFilterWeight>> {
        return db.observeSmartFilterFeedback().map { rows ->
            rows.mapNotNull { row ->
                val category = Category.entries.firstOrNull { it.name == row.suggestedCategory } ?: return@mapNotNull null
                SmartFilterWeight(
                    token = row.token,
                    category = category,
                    acceptedCount = row.acceptedCount.toLong(),
                    rejectedCount = row.rejectedCount.toLong(),
                )
            }
        }
    }

    override suspend fun recordFeedback(token: String, category: Category, accepted: Boolean) {
        ensureFeedbackLoaded()
        db.recordSmartFilterFeedback(token, category.name, accepted)
    }
}
