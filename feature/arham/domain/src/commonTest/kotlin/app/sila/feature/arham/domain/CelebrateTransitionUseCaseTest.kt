package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * اختبارات كاشف الانتقال أحمر←أخضر (T8-06 · خطة §D.3-5): مرة واحدة في فترة الأسبوع
 * لكل جهة، باب الكونفيتي المُعطَّلة لا تُطلق ولا تُعلِّم، والعلامة بعد القرار حصراً.
 */
class CelebrateTransitionUseCaseTest {

    private class FakePolicy : ArhamCelebrationPolicy {
        var confettiEnabled = true
        val marks = mutableListOf<Pair<Long, Long>>()
        private val celebrated = mutableMapOf<Long, Long>()

        override suspend fun isConfettiEnabled(): Boolean = confettiEnabled

        override suspend fun celebratedPeriodKey(contactId: Long): Long? = celebrated[contactId]

        override suspend fun markCelebrated(contactId: Long, periodKey: Long) {
            marks += contactId to periodKey
            celebrated[contactId] = periodKey
        }
    }

    /** ساعة قابلة للتحريك — الفترة 0: [0, PERIOD_MILLIS)، الفترة 1: بعدها. */
    private class MovableClock(var millis: Long = 1_000_000L) : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private fun useCase(policy: FakePolicy, clock: MovableClock) =
        CelebrateTransitionUseCase(policy = policy, clock = clock)

    @Test
    fun overdueToOk_firesOncePerPeriod_andMarksAfterDecision() = runTest {
        val policy = FakePolicy()
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        assertTrue(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertEquals(1, policy.marks.size)

        // الإطلاق نفسه داخل الفترة (إعادة تركيب/انبعاث ثانٍ) لا يعيد الاحتفال.
        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertEquals(1, policy.marks.size)
    }

    @Test
    fun nonRedToGreenTransitions_neverFire() = runTest {
        val policy = FakePolicy()
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OK, currentStatus = ArhamStatus.OK))
        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.SOON, currentStatus = ArhamStatus.OK))
        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.SOON))
        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OK, currentStatus = ArhamStatus.OVERDUE))
        assertTrue(policy.marks.isEmpty()) // القرارات الرافضة لا تُعلِّم الفترة
    }

    @Test
    fun confettiDisabled_blocksEvenRedToGreen_withoutMarking() = runTest {
        val policy = FakePolicy().apply { confettiEnabled = false }
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertTrue(policy.marks.isEmpty())
    }

    @Test
    fun disabledConfetti_today_allowsCelebrationLaterInSamePeriod_whenReEnabled() = runTest {
        val policy = FakePolicy()
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        policy.confettiEnabled = false
        assertFalse(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        policy.confettiEnabled = true
        assertTrue(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertEquals(1, policy.marks.size) // الفترة لم تُستهلك بالقرار الرافض
    }

    @Test
    fun newPeriod_firesAgain_forSameContact() = runTest {
        val policy = FakePolicy()
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        assertTrue(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))

        clock.millis += CelebrateTransitionUseCase.PERIOD_MILLIS // فترة أسبوع جديدة
        assertTrue(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertEquals(2, policy.marks.size)
    }

    @Test
    fun periodsAreIsolated_perContact() = runTest {
        val policy = FakePolicy()
        val clock = MovableClock()
        val celebrate = useCase(policy, clock)

        assertTrue(celebrate(contactId = 5L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        // جهة أخرى في الفترة نفسها لها فترتها الخاصة.
        assertTrue(celebrate(contactId = 6L, previousStatus = ArhamStatus.OVERDUE, currentStatus = ArhamStatus.OK))
        assertEquals(
            listOf(5L to 0L, 6L to 0L),
            policy.marks,
        ) // مفتاح الفترة = millis / PERIOD_MILLIS (فترة 0 عند 1_000_000)
    }
}
