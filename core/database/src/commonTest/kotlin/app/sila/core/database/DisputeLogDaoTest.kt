package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.model.DisputeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبار قراءة SSOT لجدول `dispute_logs` (S1-5 — تذكرة #61): الاستعلام المرتّب
 * يقرأ الصفوف من Room حصراً بترتيب `updatedAt` تنازلياً — بلا أي قائمة مدوّنة،
 * وباستقلال تام عن مسار الكتابة الذي بقي خارج المرحلة 1 («توثيق نزاع» — المرحلة 2).
 */
class DisputeLogDaoTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun observeDisputeLogsOrderedReadsRowsFromRoomNewestFirst() = runTest {
        val baseMillis = 1_700_000_000_000L
        val older = DisputeLogEntity(
            id = 1L,
            serviceCloudId = "srv_old",
            description = "نزاع قديم اكتمل",
            status = DisputeStatus.RESOLVED,
            createdAt = baseMillis,
            updatedAt = baseMillis + 1_000L,
            resolvedAt = baseMillis + 900L
        )
        val newest = DisputeLogEntity(
            id = 2L,
            serviceCloudId = "srv_new",
            description = "نزاع مفتوح أحدث",
            status = DisputeStatus.OPEN,
            createdAt = baseMillis + 2_000L,
            updatedAt = baseMillis + 5_000L
        )
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.seedDisputesForTest(listOf(newest, older))
            val rows = db.room.disputeLogEntityDao().observeDisputeLogsOrdered().first()

            assertEquals(2, rows.size, "الاستعلام يقرأ الصفوف من Room حصراً — القراءة لا تُصنع من الفراغ")
            assertEquals(listOf(2L, 1L), rows.map { it.id }, "الأحدث وفق `updatedAt` يتصدر الاستعلام المرتّب")
            assertEquals("srv_new", rows.first().serviceCloudId)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun observeDisputeLogsOrderedReturnsEmptyListForFreshRoom() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val rows = db.room.disputeLogEntityDao().observeDisputeLogsOrdered().first()
            assertEquals(0, rows.size, "قاعدة جديدة بلا نزاعات تقرأ قائمة فارغة حقيقية من Room")
        } finally {
            db.closeConnection()
        }
    }
}
