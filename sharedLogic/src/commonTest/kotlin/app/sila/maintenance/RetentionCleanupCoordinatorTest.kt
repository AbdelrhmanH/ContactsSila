package app.sila.maintenance

import app.sila.core.common.AppClock
import app.sila.core.database.dao.CleanupReport
import app.sila.core.database.dao.DataRetentionCleanupDao
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات انحدار F-02 لمشغِّل صيانة الاستبقاء ([RetentionCleanupCoordinator]):
 * - flight واحد لكل عملية: نداءات متزامنة أو إطلاق مزدوج لا يكرر التنفيذ.
 * - احتواء الإخفاق: فشل الـ DAO لا يسقط التطبيق (تقرير null) ولا يُعاد في نفس العملية.
 * - الإلغاء ليس إخفاقاً: يُعاد رميه التزاماً بعقد Coroutines.
 * - الساعة محقونة: القيمة الممررة للـ DAO تأتي من [AppClock].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetentionCleanupCoordinatorTest {

    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_760_000_000_000L)
    }

    private val report = CleanupReport(deletedCallEvents = 3, deletedInboxMessages = 2, archivedDisputes = 1, deletedTombstonedContacts = 4)

    private class FakeCleanupDao(
        private val report: CleanupReport,
        private val failure: Throwable? = null,
        private val suspendMillis: Long = 0,
        private val neverReturns: Boolean = false,
    ) : DataRetentionCleanupDao {
        var callCount = 0
            private set
        var lastNowMillis: Long? = null
            private set

        override suspend fun cleanupExpiredData(nowMillis: Long): CleanupReport {
            callCount++
            lastNowMillis = nowMillis
            if (neverReturns) awaitCancellation()
            if (suspendMillis > 0) delay(suspendMillis)
            failure?.let { throw it }
            return report
        }
    }

    @Test
    fun runOnce_executesCleanupOnce_passingInjectedClock_andPublishesReport() = runTest {
        val dao = FakeCleanupDao(report)
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        val result = coordinator.runOnce()

        assertEquals(report, result)
        assertEquals(1, dao.callCount)
        assertEquals(1_760_000_000_000L, dao.lastNowMillis, "حدود الاستبقى تُحسب من الساعة المحقونة حصراً")
        assertEquals(report, coordinator.lastReport.first { it != null })
    }

    @Test
    fun runOnce_isSingleFlight_underConcurrentCalls() = runTest {
        val dao = FakeCleanupDao(report, suspendMillis = 10)
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        val (firstResult, secondResult) = coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.runOnce() }
            val second = async { coordinator.runOnce() }
            first to second
        }

        assertEquals(report, firstResult.await(), "النداء الأول ينفّذ التنظيف ويعيد التقرير")
        assertNull(secondResult.await(), "النداء المتزامن الثاني لا ينفّذ شيئاً (flight واحد لكل عملية)")
        assertEquals(1, dao.callCount, "الـ DAO استُدعي مرة واحدة حصراً رغم التزامن")
    }

    @Test
    fun launchIn_executesExactlyOnce_despiteDuplicateLaunches() = runTest {
        val dao = FakeCleanupDao(report)
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        // إطلاقان متتاليان (محاكاة مساري Android/iOS) على نفس نطاق الاختبار.
        coordinator.launchIn(this)
        coordinator.launchIn(this)
        advanceUntilIdle()

        assertEquals(1, dao.callCount, "الإطلاق المزدوج (مسارا Android/iOS) لا يكرر التنفيذ")
        assertEquals(report, coordinator.lastReport.value)
    }

    @Test
    fun runOnce_containsDaoFailure_returnsNull_andStaysSingleFlightForTheProcess() = runTest {
        val dao = FakeCleanupDao(report, failure = IllegalStateException("DB corrupted"))
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        val result = coordinator.runOnce()

        assertNull(result, "إخفاق التنظيف يُسجَّل ولا يُرمى — لا يسقط التطبيق عند الإقلاع")
        assertEquals(1, dao.callCount)
        assertNull(coordinator.lastReport.value)

        // نفس العملية لا تُعيد المحاولة تلقائياً (العلم مرفوع) — الإقلاع التالي هو نقطة الاستئناف.
        assertNull(coordinator.runOnce())
        assertEquals(1, dao.callCount)
    }

    @Test
    fun runOnce_rethrowsCancellation_andRemainsSingleFlight() = runTest {
        val dao = FakeCleanupDao(report, neverReturns = true)
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.runOnce() }
        assertTrue(job.isActive, "التنظيف موقوف داخل الـ DAO قبل الإلغاء")

        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled, "الإلغاء يُعاد رميه التزاماً بعقد Coroutines (ليس إخفاقاً مُحتَوَى)")
        assertEquals(1, dao.callCount)

        // الإلغاء يترك العلم مرفوعاً — لا إعادة تنفيذ في نفس العملية (موثق في الكلاس).
        assertNull(coordinator.runOnce())
        assertEquals(1, dao.callCount)
        assertNull(coordinator.lastReport.value)
    }

    @Test
    fun runOnce_secondCallAfterSuccess_neverTouchesTheDaoAgain() = runTest {
        val dao = FakeCleanupDao(report)
        val coordinator = RetentionCleanupCoordinator(dao, fixedClock)

        assertNotNull(coordinator.runOnce())
        assertNull(coordinator.runOnce(), "النداءات اللاحقة للنجاح تعيد null بلا أي وصول للقاعدة")
        assertEquals(1, dao.callCount)
    }
}
