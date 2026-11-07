package app.sila.arham.notification

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.OccasionAlertKind
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * اختبارات ملكية نطاق الجدولة وإيقافها القابل للانتظار (تكامل دورة حياة الموارد —
 * دمج PR #46 في T8): كانت `scheduleAt` تُفرخ نطاقاً منفصلاً لكل نداء بلا مالك ولا
 * إلغاء — فبقى عملُ الجدولة (قراءة DataStore/Room) حياً عبر إغلاق الحاوية، بلا أي
 * انتظار ممكن. الآن النطاق مملوك للمجدول حصراً، و
 * [IosArhamNotificationScheduler.drainForShutdown] يلغي و**ينتظر** قبل أي إغلاق
 * تابعين — والمندوب بذا الحساب عبر [IosArhamNotificationCenterRegistrar]. حتمي بلا
 * نوم: مجدول اختبار قياسي ومقابض `Job` حقيقية، ومركز إشعارات غائب في مضيف K/N.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosArhamNotificationShutdownTest {

    private val fixedMillis = 1_760_000_000_000L
    private val clock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(fixedMillis)
    }
    private val utc: TimeZone = TimeZone.UTC
    private val unconfined = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit
    }

    private fun planner(repo: FakeArhamRepository): PlanArhamDailyNotificationUseCase =
        PlanArhamDailyNotificationUseCase(
            getPriorityList = GetArhamPriorityListUseCase(
                repository = repo,
                clock = clock,
                dispatchers = unconfined,
            ),
            observeUpcomingOccasions = ObserveUpcomingOccasionsUseCase(
                repository = repo,
                clock = clock,
                timeZone = utc,
                dispatchers = unconfined,
            ),
            clock = clock,
            timeZone = utc,
        )

    private fun scheduler(
        repo: FakeArhamRepository,
        schedulingContext: CoroutineContext = Dispatchers.Default,
        centerTouches: MutableStateFlow<Int>? = null,
    ): IosArhamNotificationScheduler =
        IosArhamNotificationScheduler(
            planner = planner(repo),
            preferences = FakeUserPreferencesDataSource(),
            strings = MarkerStrings,
            timeZone = utc,
            schedulingContext = schedulingContext,
            centerProvider = {
                centerTouches?.let { it.value = it.value + 1 }
                null
            },
        )

    private object MarkerStrings : IosArhamNotificationStrings {
        override fun digestTitle(): String = "عنوان_مرسَب"
        override fun digestBody(headlineDisplayName: String, additionalOverdueCount: Int): String = "جسد_مرسَب"
        override fun occasionBody(kind: OccasionAlertKind, type: String, displayName: String): String = "مناسبة_مرسَبة"
        override fun callNowActionTitle(): String = "اتصل_الآن"
        override fun remindTomorrowActionTitle(): String = "ذكّرني_غداً"
    }

    @Test
    fun drainForShutdown_joinsOwnedScope_andIsIdempotent() = runTest {
        val scheduler = scheduler(repo = FakeArhamRepository(emptyList()))
        scheduler.scheduleAt(fireAtMillis = fixedMillis) // جسم قصير على Default (مسار مركز-غائب)

        scheduler.drainForShutdown()
        scheduler.drainForShutdown() // الاستدعاء المتكرر آمن — النطاق مملوك ومُتَمَّ

        @Suppress("USELESS_ELVIS")
        val scopeJob = scheduler.schedulingScope.coroutineContext[Job]
        assertFalse(
            scopeJob?.isActive ?: false,
            "the drain must cancel the scheduler-owned scope"
        )
    }

    @Test
    fun drainForShutdown_cancelsUnstartedScheduling_withoutTouchingCenter() = runTest {
        val centerTouches = MutableStateFlow(0)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = scheduler(
            repo = FakeArhamRepository(emptyList()),
            schedulingContext = testDispatcher,
            centerTouches = centerTouches,
        )
        scheduler.scheduleAt(fireAtMillis = fixedMillis) // الكوروتين في قائمة مجدول الاختبار — الجسد لم يبدأ

        // استدعاء مباشر متزامن: إلغاء النطاق يُسجَّل قبل جري أي حدث في قائمة المجدول
        // (FIFO)، والانضمام يعلّق — ثم يضخّ مجدول runTest حدث الجسد فيرى الإلغاء
        // ويتخطى الجسد حصراً — صفر لمس للمركز، بلا انسداد ولا انتظار حقيقي.
        scheduler.drainForShutdown()

        assertEquals(
            0,
            centerTouches.value,
            "cancelling unstarted scheduling must prevent any notification-center touch"
        )
        assertFalse(
            scheduler.schedulingScope.coroutineContext[Job]?.isActive ?: false,
            "the owned scope must end cancelled after the drain"
        )
    }

    @Test
    fun registrarDrainForShutdown_forwardsToDelegateScope_andCancelsIt() = runTest {
        val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delegate = IosArhamNotificationCenterDelegate(
            dispatchScope = dispatchScope,
            preferences = FakeUserPreferencesDataSource(),
            planner = planner(FakeArhamRepository(emptyList())),
            scheduler = scheduler(FakeArhamRepository(emptyList())),
            strings = MarkerStrings,
        )
        val registrar = IosArhamNotificationCenterRegistrar(delegate)

        registrar.drainForShutdown()
        registrar.drainForShutdown() // idempotent

        assertFalse(
            dispatchScope.coroutineContext[Job]?.isActive ?: false,
            "the registrar's drain must cancel+join the delegate's dispatch scope"
        )
    }
}
