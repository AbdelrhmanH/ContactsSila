package app.sila.maintenance

import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.feature.arham.domain.MaterializeTopFiveUseCase
import app.sila.feature.arham.domain.TopFiveMaterializationOutcome
import app.sila.feature.arham.domain.TopFiveMaterializationRepository
import app.sila.feature.arham.domain.TopFiveMaterializationTarget
import app.sila.feature.arham.domain.TopFiveMaterializationWriteCounts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات انحدار F-1 لمشغِّل تجسيد «أهم 5» ([ArhamMaterializationCoordinator] — T8-15):
 * - flight واحد لكل عملية: نداءات متزامنة أو إطلاق مزدوج (مسارا Android/iOS) لا يكرر التنفيذ.
 * - نتيجة التشغيل الناجح تُنشر على [ArhamMaterializationCoordinator.lastOutcome].
 * - احتواء الإخفاق: فشل المستودع لا يسقط التطبيق (نتيجة null) ولا يُعاد في نفس العملية —
 *   والخاد يبقى غير مرفوع (§F.10) فتُستكمل العملية عند الإقلاع التالي.
 * - الإلغاء ليس إخفاقاً: يُعاد رميه التزاماً بعقد Coroutines.
 * الاختبار فوق الحالة الاستخدامية الحقيقية فوق مستودع وهمي — يثبت أن المشغِّل يستدعي
 * منطق §F الفعلي لا نسخةً موازية منه.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamMaterializationCoordinatorTest {

    /** مستودع وهمي (مرآة `MaterializeTopFiveUseCaseTest`) قابلٌ لحقن العطل والتعليق. */
    private class FakeTopFiveMaterializationRepository(
        private val materialized: Boolean = false,
        private val selections: List<TopFiveSelection> = emptyList(),
        private val liveContacts: List<Contact> = emptyList(),
        private val writeCounts: TopFiveMaterializationWriteCounts =
            TopFiveMaterializationWriteCounts(0, 0, 0),
        private val failure: Throwable? = null,
        private val suspendMillis: Long = 0,
        private val neverReturns: Boolean = false,
    ) : TopFiveMaterializationRepository {
        var callCount = 0
            private set

        override suspend fun isMaterialized(): Boolean = materialized

        override suspend fun markMaterialized() = Unit

        override suspend fun pendingSelections(): List<TopFiveSelection> = selections

        override suspend fun hasLiveContacts(): Boolean = liveContacts.isNotEmpty()

        override suspend fun findContactsBySystemContactIds(
            systemContactIds: Set<String>,
        ): List<Contact> = liveContacts.filter { it.systemContactId in systemContactIds }

        override suspend fun applyMaterialization(
            targets: List<TopFiveMaterializationTarget>,
        ): TopFiveMaterializationWriteCounts {
            callCount++
            if (neverReturns) awaitCancellation()
            if (suspendMillis > 0) delay(suspendMillis)
            failure?.let { throw it }
            return writeCounts
        }
    }

    private fun contact(id: Long, systemContactId: String) = Contact(
        id = id,
        systemContactId = systemContactId,
        name = ContactName(displayName = "جهة $id"),
    )

    private fun selection(systemContactId: String) = TopFiveSelection(
        systemContactId = systemContactId,
        displayName = "العم أيمن",
        relation = Relation.UNCLE_PATERNAL,
        frequency = ArhamFrequency.WEEKLY,
    )

    private fun readyRepository(
        writeCounts: TopFiveMaterializationWriteCounts = TopFiveMaterializationWriteCounts(1, 0, 0),
        suspendMillis: Long = 0,
        neverReturns: Boolean = false,
        failure: Throwable? = null,
        materialized: Boolean = false,
    ) = FakeTopFiveMaterializationRepository(
        materialized = materialized,
        selections = listOf(selection("sys_one")),
        liveContacts = listOf(contact(7L, "sys_one")),
        writeCounts = writeCounts,
        suspendMillis = suspendMillis,
        neverReturns = neverReturns,
        failure = failure,
    )

    @Test
    fun runOnce_executesMaterializationViaTheRealUseCase_andPublishesOutcome() = runTest {
        val repository = readyRepository()
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        val result = coordinator.runOnce()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(result)
        assertEquals(1, completed.applied)
        assertEquals(1, repository.callCount)
        assertEquals(result, coordinator.lastOutcome.first { it != null })
    }

    @Test
    fun runOnce_isSingleFlight_underConcurrentCalls() = runTest {
        val repository = readyRepository(suspendMillis = 10)
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        val (firstResult, secondResult) = coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.runOnce() }
            val second = async { coordinator.runOnce() }
            first to second
        }

        assertIs<TopFiveMaterializationOutcome.Completed>(firstResult.await(), "النداء الأول ينفّذ التجسيد ويعيد النتيجة")
        assertNull(secondResult.await(), "النداء المتزامن الثاني لا ينفّذ شيئاً (flight واحد لكل عملية)")
        assertEquals(1, repository.callCount, "المستودع استُدعي مرة واحدة حصراً رغم التزامن")
    }

    @Test
    fun launchIn_executesExactlyOnce_despiteDuplicateLaunches() = runTest {
        val repository = readyRepository()
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        // إطلاقان متتاليان (محاكاة مساري Android/iOS) على نفس نطاق الاختبار.
        coordinator.launchIn(this)
        coordinator.launchIn(this)
        advanceUntilIdle()

        assertEquals(1, repository.callCount, "الإطلاق المزدوج (مسارا Android/iOS) لا يكرر التنفيذ")
        assertIs<TopFiveMaterializationOutcome.Completed>(coordinator.lastOutcome.value)
    }

    @Test
    fun runOnce_containsRepositoryFailure_returnsNull_andStaysSingleFlightForTheProcess() = runTest {
        val repository = readyRepository(failure = IllegalStateException("DB corrupted"))
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        val result = coordinator.runOnce()

        assertNull(result, "إخفاق التجسيد يُسجَّل ولا يُرمى — لا يسقط التطبيق عند الإقلاع")
        assertEquals(1, repository.callCount)
        assertNull(coordinator.lastOutcome.value)

        // نفس العملية لا تُعيد المحاولة تلقائياً (العلم مرفوع) — الإقلاع التالي هو نقطة الاستئناف؛
        // والخاد يبقى غير مرفوع (§F.10) فاكتمالٌ idempotent عند الإقلاع التالي.
        assertNull(coordinator.runOnce())
        assertEquals(1, repository.callCount)
    }

    @Test
    fun runOnce_rethrowsCancellation_andRemainsSingleFlight() = runTest {
        val repository = readyRepository(neverReturns = true)
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.runOnce() }
        assertTrue(job.isActive, "التجسيد موقوف داخل المستودع قبل الإلغاء")

        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled, "الإلغاء يُعاد رميه التزاماً بعقد Coroutines (ليس إخفاقاً مُحتَوَى)")
        assertEquals(1, repository.callCount)

        // الإلغاء يترك العلم مرفوعاً — لا إعادة تنفيذ في نفس العملية (موثق في الكلاس).
        assertNull(coordinator.runOnce())
        assertEquals(1, repository.callCount)
        assertNull(coordinator.lastOutcome.value)
    }

    @Test
    fun runOnce_secondCallAfterSuccess_neverTouchesTheRepositoryAgain() = runTest {
        val repository = readyRepository()
        val coordinator = ArhamMaterializationCoordinator(MaterializeTopFiveUseCase(repository))

        assertNotNull(coordinator.runOnce())
        assertNull(coordinator.runOnce(), "النداءات اللاحقة للنجاح تعيد null بلا أي وصول للمستودع")
        assertEquals(1, repository.callCount)
    }
}
