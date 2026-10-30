package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات العقد المجمد [ContactsPlatformSource] (Issue #22) تُنفَّذ ضد
 * [FakeContactsPlatformSource] على Android Host وiOS المحاكي.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsPlatformSourceContractTest {

    @Test
    fun readPage_returnsConfiguredBatchesWithContinueFlag() = runTest {
        val fake = FakeContactsPlatformSource()
            .withPages(
                listOf(
                    FakeContactsPlatformSource.contact("a"),
                    FakeContactsPlatformSource.contact("b")
                ),
                listOf(FakeContactsPlatformSource.contact("c"))
            )

        val firstPage = fake.readPage(offset = 0, limit = 2)
        assertEquals(listOf("a", "b"), firstPage.contacts.map { it.systemContactId })
        assertEquals(0, firstPage.offset)
        assertEquals(2, firstPage.limit)
        assertTrue(firstPage.hasMore)

        val secondPage = fake.readPage(offset = 2, limit = 2)
        assertEquals(listOf("c"), secondPage.contacts.map { it.systemContactId })
        assertFalse(secondPage.hasMore)

        val beyondEnd = fake.readPage(offset = 4, limit = 2)
        assertTrue(beyondEnd.contacts.isEmpty())
        assertFalse(beyondEnd.hasMore)
    }

    @Test
    fun readDelta_nullSnapshot_surfacesFullResyncBuildOnAllPagesWithNoDeletions() = runTest {
        val fake = FakeContactsPlatformSource()
            .withPages(
                listOf(FakeContactsPlatformSource.contact("a")),
                listOf(FakeContactsPlatformSource.contact("b"))
            )

        val delta = fake.readDelta(previousSnapshot = null)

        assertTrue(delta.isFullResync, "Null snapshot must surface as full resync (ADR-0010).")
        assertEquals(listOf("a", "b"), delta.upserted.map { it.systemContactId })
        assertTrue(delta.deletedSystemContactIds.isEmpty(), "Full resync must never carry deletions.")
        assertNotNull(delta.newSnapshot)
        assertEquals(1, fake.readDeltaRequestCount)
    }

    @Test
    fun readDelta_snapshotWithoutPlatformMarker_isTreatedLikeAnInvalidSnapshot() = runTest {
        val fake = FakeContactsPlatformSource()

        val delta = fake.readDelta(previousSnapshot = ContactsSnapshot())

        assertTrue(delta.isFullResync, "Snapshot without a platform marker is invalid → full resync.")
        assertTrue(delta.deletedSystemContactIds.isEmpty())
    }

    @Test
    fun readDelta_isIdempotentAcrossRepeatedCallsWithTheSameSnapshot() = runTest {
        val snapshot = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_ANDROID)
        val fake = FakeContactsPlatformSource().withDeltas(
            ContactsDelta(
                upserted = listOf(FakeContactsPlatformSource.contact("changed")),
                deletedSystemContactIds = listOf("gone"),
                newSnapshot = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_ANDROID),
                isFullResync = false
            )
        )

        val first = fake.readDelta(snapshot)
        assertEquals(listOf("changed"), first.upserted.map { it.systemContactId })

        // إعادة نفس اللقطة تنتج دلتا فارغة نظيفة (idempotent) — لا اكتمال ثنائي.
        val second = fake.readDelta(snapshot)
        assertTrue(second.upserted.isEmpty())
        assertTrue(second.deletedSystemContactIds.isEmpty())
        assertFalse(second.isFullResync)
        assertEquals(snapshot.platformMarker, second.newSnapshot?.platformMarker)
    }

    @Test
    fun permissionTransitions_areEmittedThroughObservePermissionState() = runTest {
        val fake = FakeContactsPlatformSource(initialPermission = ContactsPermissionState.DENIED)
        val observed = mutableListOf<ContactsPermissionState>()

        val collector = launch(Unconfined) {
            fake.observePermissionState().take(4).toList(observed)
        }

        fake.grantPermission()
        fake.limitPermission()
        fake.denyPermission()
        fake.permanentlyDenyPermission()

        collector.join()
        assertEquals(
            listOf(
                ContactsPermissionState.DENIED,
                ContactsPermissionState.GRANTED,
                ContactsPermissionState.LIMITED,
                ContactsPermissionState.DENIED
            ),
            observed
        )
    }

    @Test
    fun changeSignals_areDirtyOnlyAndCarryNoRecordIdentity() = runTest {
        val fake = FakeContactsPlatformSource()
        val observed = mutableListOf<ContactsChangeSignal>()

        val collector = launch(Unconfined) {
            fake.observeChangeSignals().take(2).toList(observed)
        }

        fake.sendChangeSignal(occurredAtMillis = 1L)
        fake.sendChangeSignal(occurredAtMillis = 2L)

        collector.join()
        assertEquals(2, observed.size)
        assertTrue(observed.all { it.occurredAtMillis > 0 })
    }

    @Test
    fun limitedPermission_neverProducesDeletionsOrFullResyncDeletion() = runTest {
        val fake = FakeContactsPlatformSource()
            .withPages(listOf(FakeContactsPlatformSource.contact("visible-only")))
        // محاكاة ADR-0010 §5: النطاق الجزئي لا ينتج حذف سجل ظنّاً نقص ظهوره.
        fake.withDeltas(
            ContactsDelta(
                upserted = fake.pages.flatten(),
                deletedSystemContactIds = emptyList(),
                newSnapshot = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_IOS),
                isFullResync = false
            )
        )
        fake.limitPermission()

        val delta = fake.readDelta(previousSnapshot = null)
        assertNull(delta.deletedSystemContactIds.singleOrNull())
        assertFalse(delta.isFullResync)
        assertEquals(ContactsPermissionState.LIMITED, fake.permissionState.value)
    }
}
