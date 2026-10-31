package app.sila.feature.contacts.data

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.ContactsSyncState
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبار أداء الاستيراد الكامل لـ 5000 جهة (Issue #23 — Performance test).
 *
 * **قيد بيئة التنفيذ:** يعمل هذا الاختبار على JVM مضيف (androidHostTest) — لا محاكي جهاز
 * متوسط الفئة هنا؛ ميزانية الأداء على الجهاز الحقيقي (< 500ms للإطار الأول من Room وبدون
 * تقطّع تمرير) تُثبَت عبر قياس CI/الجهاز وتُرفَق في PR الميزة. السقف هنا سخي عمداً
 * لكشف الانحدارات الكارثية فقط (وليس قياس دقة للأداء على الأجهزة).
 */
class ContactsSyncEnginePerformanceTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun fullImport_5000Contacts_completesWithinJvmHostCeiling() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contacts = (0 until 5000).map { index ->
                app.sila.feature.contacts.domain.PlatformContact(
                    systemContactId = "perf_$index",
                    lookupKey = "perf_$index",
                    name = ContactName(displayName = "جهة أداء $index"),
                    phones = listOf(
                        Phone(
                            raw = "010${index.toString().padStart(8, '0')}",
                            e164 = "+2010${index.toString().padStart(8, '0')}",
                            isPrimary = true
                        )
                    )
                )
            }
            val source = FakeContactsPlatformSource().withPages(*contacts.chunked(500).toTypedArray())
            source.snapshotForDelta = ContactsSnapshot(
                platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
                androidRawContacts = mapOf("batch" to AndroidRawContactState(version = 1L))
            )
            val engine = ContactsSyncEngine(
                platformSource = source,
                syncDao = db,
                clock = app.sila.core.common.SystemAppClock,
                batchLimit = 500
            )

            val startedAt = System.nanoTime()
            engine.runSyncCycle(ContactsPermissionState.GRANTED)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(ContactsSyncState.READY, engine.state.value)
            assertEquals(5000, db.observeContacts().first().size)
            assertNotNullSnapshot(db)
            assertTrue(
                elapsedMs < PERF_CEILING_MS,
                "JVM-host import of 5000 contacts took ${elapsedMs}ms — exceeded ceiling ${PERF_CEILING_MS}ms"
            )
            println("PERF[ContactsSyncEngine]: 5000 contacts imported in ${elapsedMs}ms (JVM host)")
        } finally {
            db.closeConnection()
        }
    }

    private suspend fun assertNotNullSnapshot(db: LocalSilaDatabase) {
        kotlin.test.assertNotNull(db.loadSyncSnapshotJson())
    }

    private companion object {
        /** سقف سخي لكشف الانحدارات الكارثية على JVM مضيف فقط (انظر KDoc للقيد البيئي). */
        const val PERF_CEILING_MS = 60_000L
    }
}
