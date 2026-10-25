package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import kotlinx.coroutines.Dispatchers
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DisputeStatus
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSilaDatabaseTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun observeContacts_filtersByArabicNormalizedQuery() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // البحث عن "احمد" بدون همزة يجد "العم أحمد"
            val results = db.observeContacts(searchQuery = "احمد").first()
            assertEquals(1, results.size)
            assertEquals("العم أحمد", results.first().name.displayName)
        } finally {
            db.close()
        }
    }

    @Test
    fun observeContacts_filtersByCategory() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val family = db.observeContacts(category = Category.FAMILY).first()
            assertEquals(3, family.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun cleanupExpiredData_enforcesDataRetentionPolicy() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val dayMs = 86_400_000L
            val now = 1_800_000_000_000L

            db.seedCallEventsForTest(
                listOf(
                    CallEventEntity("old_1", "+201011112222", CallDirection.OUT, 45, now - (400 * dayMs), CallSource.CALL_LOG),
                    CallEventEntity("recent_1", "+201011112222", CallDirection.OUT, 60, now - (10 * dayMs), CallSource.CALL_LOG)
                )
            )
            db.seedDisputesForTest(
                listOf(
                    DisputeLogEntity(
                        id = 1L,
                        serviceCloudId = "srv_1",
                        description = "تأخر في الموعد",
                        status = DisputeStatus.RESOLVED,
                        createdAt = now - (120 * dayMs),
                        updatedAt = now - (100 * dayMs),
                        resolvedAt = now - (100 * dayMs)
                    )
                )
            )

            val report = db.cleanupExpiredData(now)
            assertEquals(1, report.deletedCallEvents)
            assertEquals(1, report.archivedDisputes)
            assertTrue(report.deletedInboxMessages == 0)
        } finally {
            db.close()
        }
    }

    @Test
    fun sqliteDiskPersistence_writeCloseReopenAndReadPreservesRelationalData() = runTest {
        val tempDbPath = "build/tmp_test_sila_${Random.nextLong(100_000, 999_999)}.db"

        // 1. افتح قاعدة البيانات على القرص واكتب جهة اتصال جديدة مع أرقام ومعلومات أرحام
        val db1 = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        val newRelative = Contact(
            id = 99L,
            systemContactId = "sys_99",
            name = ContactName(displayName = "الجد إبراهيم", firstName = "إبراهيم"),
            phones = listOf(Phone(raw = "01077778888", e164 = "+201077778888", isPrimary = true)),
            category = Category.FAMILY,
            isFavorite = true,
            arham = ArhamInfo(
                relation = Relation.GRANDFATHER,
                frequency = ArhamFrequency.WEEKLY,
                lastContactedAt = 1_760_000_000_000L
            )
        )
        db1.upsertContact(newRelative)
        db1.close()

        // 2. أعد فتح قاعدة البيانات من نفس الملف على القرص وتحقق من قراءة البيانات العلائقية
        val db2 = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db2.getContactById(99L)
            assertNotNull(loaded)
            assertEquals("الجد إبراهيم", loaded.name.displayName)
            assertEquals("+201077778888", loaded.phones.firstOrNull()?.e164)
            assertEquals(Relation.GRANDFATHER, loaded.arham?.relation)
            assertEquals(1_760_000_000_000L, loaded.arham?.lastContactedAt)
        } finally {
            db2.close()
        }
    }
}
