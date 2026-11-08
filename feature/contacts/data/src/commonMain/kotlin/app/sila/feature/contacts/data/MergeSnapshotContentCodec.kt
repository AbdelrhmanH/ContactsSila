package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.MergeSnapshotContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * مُغلف/فاكّ حمولة لقطة الدمج إلى/من JSON (T7 · Issue #28 — عقد `contacts_sync_checkpoints`:
 * حمولة معتمة يملك فكّها مستهلكو الواجهة). تنسيق v1: أعضاء ما-قبل-الدمج بأصفارهم الأصلية
 * (Contact قابل للتسلسل بذاته — `:core:model`) + خريطة بطاقات vCard للإعادة الإنشاء
 * المنصوي (iOS — agy F3a) + قوائم إعادة التوجيه للاسترجاع بالعكس.
 *
 * [decode] يُعيد `null` عند أي فساد/إصدار غير معروف — يُعامَل كلقطة غير صالحة (فشل
 * الاسترجاع صادقاً بلا أي كتابة، ولا يُلمس القفل).
 */
object MergeSnapshotContentCodec {

    private const val FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class ContentEnvelope(
        val v: Int,
        val members: List<app.sila.core.model.Contact>,
        val absorbedSystemIds: List<String>,
        val callLogIds: List<String> = emptyList(),
        /** contactId القديم لكل callLogId (خريطة callLogId → oldContactId). */
        val callEventOldContactIds: Map<String, Long> = emptyMap(),
        val parentLinkChildIds: List<Long> = emptyList(),
        val parentLinkOldParentIds: Map<Long, Long> = emptyMap(),
        val absorbedVCards: Map<String, String> = emptyMap(),
        val absorbedRawIds: Map<String, List<String>> = emptyMap(),
    )

    fun encode(content: MergeSnapshotContent): String = json.encodeToString(
        ContentEnvelope(
            v = FORMAT_VERSION,
            members = content.members,
            absorbedSystemIds = content.absorbedSystemIds,
            callLogIds = content.callEventRepoints.map { it.callLogId },
            callEventOldContactIds = content.callEventRepoints.associate { it.callLogId to it.oldContactId },
            parentLinkChildIds = content.parentLinkRepoints.map { it.childContactId },
            parentLinkOldParentIds = content.parentLinkRepoints.associate { it.childContactId to it.oldParentId },
            absorbedVCards = content.absorbedVCards,
            absorbedRawIds = content.absorbedRawIds,
        ),
    )

    fun decode(payloadJson: String): MergeSnapshotContent? = runCatching {
        val envelope = json.decodeFromString<ContentEnvelope>(payloadJson)
        if (envelope.v != FORMAT_VERSION) return null
        MergeSnapshotContent(
            members = envelope.members,
            absorbedSystemIds = envelope.absorbedSystemIds,
            callEventRepoints = envelope.callLogIds.map { callLogId ->
                MergeSnapshotContent.CallEventRepoint(
                    callLogId = callLogId,
                    oldContactId = envelope.callEventOldContactIds[callLogId] ?: 0L,
                )
            },
            parentLinkRepoints = envelope.parentLinkChildIds.map { childId ->
                MergeSnapshotContent.ParentLinkRepoint(
                    childContactId = childId,
                    oldParentId = envelope.parentLinkOldParentIds[childId] ?: 0L,
                )
            },
            absorbedVCards = envelope.absorbedVCards,
            absorbedRawIds = envelope.absorbedRawIds,
        )
    }.getOrNull()
}
