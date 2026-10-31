package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * مُغلف/فاكّ تغليف لقطة المنصة المعتمة إلى/من JSON (Issue #23 · ADR-0010 §4/§8).
 *
 * - تنسيق الإصدار v1: علامة المنصة + بايتات رمز iOS مشفرة Base64 + خريطة نُسَخ Android الخام.
 * - [encode] دائماً ينجح (البيانات كلها قابلة للتسلسل بحكم النطاق).
 * - [decode] يُعيد `null` عند أي فساد/إصدار غير معروف — تُعامَل النتيجة كلاقطة غير صالحة
 *   (إعادة مزامنة كاملة عبر [ContactsDelta.isFullResync]، لا حذف أبداً — ADR-0010 §5).
 *
 * البقاء خالياً من أنواع المنصات: بايتات فقط (`ByteArray` → Base64) وفق العقد المجمد.
 */
@OptIn(ExperimentalEncodingApi::class)
object ContactsSnapshotCodec {

    private const val FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class SnapshotEnvelope(
        val v: Int,
        val platform: String?,
        /** بايتات رمز iOS بترميز Base64 — `null` على Android. */
        val iosTokenB64: String? = null,
        val androidRawContacts: Map<String, AndroidRawContactState> = emptyMap(),
    )

    fun encode(snapshot: ContactsSnapshot): String = json.encodeToString(
        SnapshotEnvelope(
            v = FORMAT_VERSION,
            platform = snapshot.platformMarker,
            iosTokenB64 = snapshot.iosHistoryTokenBytes?.let { encodeBase64(it) },
            androidRawContacts = snapshot.androidRawContacts
        )
    )

    /** `null` = لقطة فاسدة أو من إصدار غير معروف — استرجاع عبر إعادة مزامنة كاملة. */
    fun decode(jsonText: String?): ContactsSnapshot? {
        if (jsonText.isNullOrBlank()) return null
        return try {
            val envelope = json.decodeFromString<SnapshotEnvelope>(jsonText)
            if (envelope.v != FORMAT_VERSION) return null
            ContactsSnapshot(
                platformMarker = envelope.platform,
                iosHistoryTokenBytes = envelope.iosTokenB64?.let { decodeBase64(it) },
                androidRawContacts = envelope.androidRawContacts
            )
        } catch (
            @Suppress("TooGenericExceptionCaught")
            _: Exception,
        ) {
            null
        }
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

    private fun decodeBase64(text: String): ByteArray = Base64.Default.decode(text)
}
