package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** اختبارات مُغلِف/فاكّ اللقطة المعتمة (Issue #23 · ADR-0010 §4). */
class ContactsSnapshotCodecTest {

    @Test
    fun roundTrip_preservesMarkerTokenBytesAndRawMap() {
        val snapshot = ContactsSnapshot(
            platformMarker = ContactsSnapshot.PLATFORM_IOS,
            iosHistoryTokenBytes = byteArrayOf(0, 1, 2, 127, -1, -128, 42),
            androidRawContacts = mapOf(
                "raw_1" to AndroidRawContactState(version = 7, lookupKey = "k1", aggregateContactId = 99L),
                "raw_2" to AndroidRawContactState(version = 3)
            )
        )
        val json = ContactsSnapshotCodec.encode(snapshot)
        val decoded = ContactsSnapshotCodec.decode(json)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun decode_returnsNull_onCorruptOrUnknownVersion() {
        assertNull(ContactsSnapshotCodec.decode(null))
        assertNull(ContactsSnapshotCodec.decode(""))
        assertNull(ContactsSnapshotCodec.decode("{corrupt!!"))
        assertNull(ContactsSnapshotCodec.decode("[]"))
        assertNull(ContactsSnapshotCodec.decode("""{"v":99,"platform":"android"}"""))
        assertNull(ContactsSnapshotCodec.decode("""{"v":1,"platform":"android","iosTokenB64":"!!!"}"""))
    }

    @Test
    fun decode_acceptsUnknownFields_forwardCompatible() {
        val decoded = ContactsSnapshotCodec.decode(
            """{"v":1,"platform":"android","futureField":"ignored","androidRawContacts":{}}"""
        )
        assertEquals(ContactsSnapshot.PLATFORM_ANDROID, decoded?.platformMarker)
        assertTrue(decoded?.iosHistoryTokenBytes == null)
    }

    @Test
    fun emptySnapshot_roundTrips() {
        val snapshot = ContactsSnapshot()
        assertEquals(snapshot, ContactsSnapshotCodec.decode(ContactsSnapshotCodec.encode(snapshot)))
    }
}
