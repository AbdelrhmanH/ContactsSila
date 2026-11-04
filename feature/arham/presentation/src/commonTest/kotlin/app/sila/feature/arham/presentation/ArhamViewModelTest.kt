package app.sila.feature.arham.presentation

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.ArhamCelebrationPolicy
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.CelebrateTransitionUseCase
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant

/**
 * اختبارات ViewModel أرحام (T8-06 · خطة §D.3-4/§D.3-5): زر الاتصال يُصدر أثر الاتصال
 * ويحفظ محاولة `IN_APP` حصراً بلا احتفال مباشراً، والكونفيتي يُطلق مرة واحدة في الفترة
 * عند انتقال أحمر←أخضر عبر كاشف انبعاثات القائمة (أول انبعاث يبذّر فقط).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamViewModelTest {

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit
    }

    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        val events = linkedMapOf<String, CallEvent>()
        val lastContactedWrites = mutableListOf<Pair<Long, Long>>()

        override fun observePendingConfirmations(): Flow<List<CallEvent>> = MutableStateFlow(emptyList())

        override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]

        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
            events.values.filter { it.timestamp in fromMillis..toMillis }

        override suspend fun upsertEvent(event: CallEvent) {
            events[event.id] = event
        }

        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
            lastContactedWrites += contactId to timestampMillis
        }
    }

    private class FakePolicy : ArhamCelebrationPolicy {
        var confettiEnabled = true
        private val celebrated = mutableMapOf<Long, Long>()

        override suspend fun isConfettiEnabled(): Boolean = confettiEnabled

        override suspend fun celebratedPeriodKey(contactId: Long): Long? = celebrated[contactId]

        override suspend fun markCelebrated(contactId: Long, periodKey: Long) {
            celebrated[contactId] = periodKey
        }
    }

    private val unconfined = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val baseMillis = 1_760_000_000_000L
    private val clock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(baseMillis)
    }

    private fun contact(id: Long, daysAgo: Int?) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = "ج$id"),
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = ArhamFrequency.WEEKLY,
            lastContactedAt = daysAgo?.let { baseMillis - it * 86_400_000L },
        ),
    )

    private fun viewModel(repo: FakeArhamRepository, eventRepo: FakeArhamCallEventRepository, policy: FakePolicy): ArhamViewModel {
        val getPriority = GetArhamPriorityListUseCase(repo, clock, unconfined)
        return ArhamViewModel(
            getPriorityList = getPriority,
            getDashboardStats = GetArhamDashboardStatsUseCase(getPriority, unconfined),
            recordDialAttempt = RecordDialAttemptUseCase(ingest = IngestCallEventUseCase(eventRepo)),
            celebrateTransition = CelebrateTransitionUseCase(policy = policy, clock = clock),
            clock = clock,
        )
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordCall_sendsDialEffectAndPersistsAttempt_neverCelebratesDirectly() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14))) // أحمر
        val eventRepo = FakeArhamCallEventRepository()
        val policy = FakePolicy()
        val vm = viewModel(repo, eventRepo, policy)

        val effects = mutableListOf<ArhamEffect>()
        val collector = launch { vm.effects.collect { effects += it } }

        vm.onIntent(ArhamIntent.RecordCall(contactId = 1L, phone = "+201000000000"))
        advanceUntilIdle()

        assertTrue(effects.contains(ArhamEffect.Dial("+201000000000")))
        // §D.3-5: لا احتفال عند التسجيل مباشرة — الاحتفال قرار الكاشف حصراً.
        assertFalse(effects.any { it is ArhamEffect.ShowCelebrationConfetti })
        val event = eventRepo.events.getValue("inapp-1-$baseMillis")
        assertEquals(CallOutcome.ATTEMPTED, event.outcome)
        assertEquals(CallConfirmation.PENDING, event.confirmation)
        assertEquals(CallSource.IN_APP, event.primarySource)
        assertEquals(CallDirection.OUT, event.direction)
        assertEquals(CallAssociation.AUTO, event.association)
        assertTrue(eventRepo.lastContactedWrites.isEmpty()) // قاعدة AC-8: المحاولة لا تُحدّث
        collector.cancel()
    }

    @Test
    fun overdueToOkTransition_firesConfetti_exactlyOncePerPeriod() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14))) // أحمر
        val vm = viewModel(repo, FakeArhamCallEventRepository(), FakePolicy())

        val effects = mutableListOf<ArhamEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle() // الانبعاث الأول يبذّر الخريطة حصراً — لا احتفال
        assertFalse(effects.any { it is ArhamEffect.ShowCelebrationConfetti })

        repo.contactsFlow.value = listOf(contact(1, daysAgo = 1)) // أحمر←أخضر
        advanceUntilIdle()
        assertEquals(1, effects.count { it is ArhamEffect.ShowCelebrationConfetti })

        // green→red→green داخل الفترة نفسها: العلامة تمنع الإطلاق الثاني (خطة §D.3-5).
        repo.contactsFlow.value = listOf(contact(1, daysAgo = 14))
        repo.contactsFlow.value = listOf(contact(1, daysAgo = 1))
        advanceUntilIdle()
        assertEquals(1, effects.count { it is ArhamEffect.ShowCelebrationConfetti })
        collector.cancel()
    }

    @Test
    fun confettiDisabled_noEffectOnRedToGreen() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14))) // أحمر
        val policy = FakePolicy().apply { confettiEnabled = false }
        val vm = viewModel(repo, FakeArhamCallEventRepository(), policy)

        val effects = mutableListOf<ArhamEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        repo.contactsFlow.value = listOf(contact(1, daysAgo = 1)) // أحمر←أخضر مع كونفيتي معطل
        advanceUntilIdle()
        assertFalse(effects.any { it is ArhamEffect.ShowCelebrationConfetti })
        collector.cancel()
    }

    @Test
    fun yellowToGreenTransition_doesNotCelebrate() = runTest {
        // أصفر←أخضر ليس انتقال أحمر←أخضر — بلا احتفال (كاشف الانتقال حصري).
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 5))) // 5/7 ≈ 0.71 أصفر
        val vm = viewModel(repo, FakeArhamCallEventRepository(), FakePolicy())

        val effects = mutableListOf<ArhamEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        repo.contactsFlow.value = listOf(contact(1, daysAgo = 1)) // أصفر←أخضر
        advanceUntilIdle()
        assertFalse(effects.any { it is ArhamEffect.ShowCelebrationConfetti })
        collector.cancel()
    }
}
