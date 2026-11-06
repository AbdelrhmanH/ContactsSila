package app.sila.arham.observer

import app.sila.core.common.AppClock
import app.sila.core.model.CallEvent
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.CallEventStateMachine
import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

/**
 * اختبارات دورة حياة المراقب (T8-12 · مراجعة مستقلة F-2 — مرآة `start/stop` idempotent
 * في عقد [ArhamCallStateObserver]): العلم الداخلي `isRegistered` (friend) يثبت أن
 * البدء يسجّل مرةً والاستدعاءات المتكررة بلا أثر، والتوقف آمنٌ قبل البدء وبعده،
 * وإعادة البدء دورةٌ نظيفة (الجلسات المفتوحة صُفت عند التوقف — [IosCallStateTracker.clear]).
 * المستودع تزييفٌ صامت: الربط لا يُستشار في دورة الحياة إطلاقاً.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosCallStateObserverLifecycleTest {

    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        override suspend fun getEvent(eventId: String): CallEvent? = null
        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> = emptyList()
        override suspend fun upsertEvent(event: CallEvent) = Unit
        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) = Unit
        override fun observePendingConfirmations(): Flow<List<CallEvent>> = MutableStateFlow(emptyList())
        override fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> = MutableStateFlow(emptyList())
    }

    private val clock = object : AppClock {
        override fun now(): Instant = Instant.parse("2026-02-27T10:00:00Z")
    }

    private fun observer() = IosCallStateObserver(
        correlate = CorrelateObservedCallEndUseCase(
            repository = FakeArhamCallEventRepository(),
            stateMachine = CallEventStateMachine(),
        ),
        clock = clock,
    )

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun start_registers_once_andRepeatedStartIsNoOp() {
        val observer = observer()
        assertFalse(observer.isRegistered, "قبل البدء لا تسجيل")

        observer.start(scope())
        assertTrue(observer.isRegistered, "البدء يسجّل CXCallObserver ضمن حياة التطبيق (AC-6)")

        observer.start(scope()) // استدعاء متكرر — يجب أن يكون بلا أثر
        assertTrue(observer.isRegistered, "بدءٌ متكرر لا يفكّ التسجيل ولا يعيد حالته (idempotent)")
    }

    @Test
    fun stop_isSafeBeforeStart_andUnregisters_repeatedStopIsNoOp() {
        val observer = observer()

        observer.stop() // قبل أي بدء — آمن
        assertFalse(observer.isRegistered, "توقفٌ قبل البدء بلا أثر ولا انهيار")

        observer.start(scope())
        assertTrue(observer.isRegistered)

        observer.stop()
        assertFalse(observer.isRegistered, "التوقف يفكّ التسجيل")

        observer.stop() // استدعاء متكرر — بلا أثر
        assertFalse(observer.isRegistered, "توقفٌ متكرر idempotent — ولا حالة تُفسد")
    }

    @Test
    fun restart_afterStop_registersAgain_withClearedCycles() {
        val observer = observer()
        val scope = scope()

        observer.start(scope)
        observer.stop()
        assertFalse(observer.isRegistered)

        observer.start(scope)
        assertTrue(
            observer.isRegistered,
            "إعادة البدء تسجّل من جديد — دورة نظيفة بعد تصفية الجلسات عند التوقف",
        )
    }
}
