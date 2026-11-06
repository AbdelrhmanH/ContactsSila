package app.sila.arham.notification

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DefaultTab
import app.sila.core.model.ImportantDate
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.core.model.UserPreferences
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * تزييف كامل لعقد تفضيلات المستخدم للاختبارات (مراجعة مستقلة F-2) — التفضيلات تُقرأ
 * حصراً في مسارات أرحام (لا كاتب)، فالمُثبِّتات `= Unit` كافية بلا سلوك كتابة.
 * مشترَط في حزمة `notification` ليعاد استخدامها في `IosReminderRequestTest`.
 */
internal class FakeUserPreferencesDataSource(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesDataSource {

    private val state = MutableStateFlow(initial)

    /** اللقطة الحالية (لحساب الموعد المتوقع في التأكيدات). */
    val current: UserPreferences get() = state.value

    override val preferences: Flow<UserPreferences> = state
    override suspend fun setThemeMode(mode: AppThemeMode) = Unit
    override suspend fun setLanguage(language: AppLanguage) = Unit
    override suspend fun setDefaultTab(tab: DefaultTab) = Unit
    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setQuietHours(startHour: Int, endHour: Int) = Unit
    override suspend fun setArhamNotificationTime(hour: Int, minute: Int) = Unit
    override suspend fun setArhamOccasionDayBeforeEnabled(enabled: Boolean) = Unit
    override suspend fun setConfettiEnabled(enabled: Boolean) = Unit
    override suspend fun setSearchRadiusKm(radiusKm: Int) = Unit
    override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) = Unit
    override suspend fun setSmartFilterEnabled(enabled: Boolean) = Unit
    override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
    override suspend fun setIsGuest(isGuest: Boolean) = Unit
    override suspend fun setOnboardingResumeStep(step: String?) = Unit
    override suspend fun setOnboardingContactsPermission(permission: String?) = Unit
    override suspend fun setOnboardingTopFiveSelections(selections: List<TopFiveSelection>) = Unit
    override suspend fun setTopFiveMaterialized(materialized: Boolean) = Unit
    override suspend fun markArhamTransitionCelebrated(contactId: Long, periodKey: Long) = Unit
    override suspend fun dismissSuggestion(id: String) = Unit
    override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) = Unit
}

/**
 * اختبارات قرار إزاحة الجدولة (T8-12 · مراجعة مستقلة F-2 — مرآة `ArhamReminderDispatchTest`
 * أندرويد): المسار المنصّي يستهلك القرار المشترك حصراً — المعطّل ⇒ إلغاءٌ واحد بلا جدولة
 * (فارق iOS الموثّق: الزنّاد المتكرر بلا مستقبِل إعادة تقييم)، والمفعّل ⇒ جدولة لحظة
 * الخطة الحقيقية فوق تزييفات حتمية (ساعة ثابتة، UTC، مستودع تزييف — مرآة مُثبّتات
 * `PlanArhamDailyNotificationUseCaseTest`) بلا إلغاء إطلاقاً.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosArhamReminderDispatchTest {

    /** مسجِّل قرارٍ منصّي مزيف — يعيد أثر الاستدعاءات بلا مركز إشعارات. */
    private class RecordingScheduler : IosArhamReminderScheduling {
        val scheduledAt = mutableListOf<Long>()
        var cancelCount = 0
        override fun scheduleAt(fireAtMillis: Long) {
            scheduledAt += fireAtMillis
        }

        override fun cancelPending() {
            cancelCount += 1
        }
    }

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit
    }

    private val unconfined = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    /** الجمعة 2026-02-27 الساعة 10:00Z — ساعة ثابتة لكل الاختبارات. */
    private val baseMillis = Instant.parse("2026-02-27T10:00:00Z").toEpochMilliseconds()

    private val clock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(baseMillis)
    }

    private val utc = TimeZone.UTC

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

    @Test
    fun disabledFeature_cancelsPendingExactlyOnce_andNeverSchedules() = runTest {
        val preferences = FakeUserPreferencesDataSource(
            UserPreferences(arhamNotificationsEnabled = false),
        )
        val scheduler = RecordingScheduler()

        IosArhamReminderDispatch.rescheduleFromNow(
            preferences = preferences,
            planner = planner(FakeArhamRepository(emptyList())), // لا يُستشار أصلاً — العلم يحكم
            scheduler = scheduler,
        )

        assertEquals(
            0,
            scheduler.scheduledAt.size,
            "المعطّل لا يُنتج جدولة إطلاقاً — إشعار متصاعم بعد التعطيل اختلاقٌ للإلحاح",
        )
        assertEquals(
            1,
            scheduler.cancelCount,
            "الإلغاء مرةٌ واحدة بالضبط — الفارق iOS الموثّق (الزنّاد المتكرر يُمسح فوراً)",
        )
    }

    @Test
    fun enabledFeature_schedulesThePlannedMoment_exactlyOnce_andNeverCancels() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                Contact(
                    id = 1,
                    systemContactId = "sys-1",
                    name = ContactName(displayName = "عم المتأخر"),
                    category = Category.FAMILY,
                    dates = emptyList(),
                    arham = ArhamInfo(
                        relation = Relation.UNCLE_PATERNAL,
                        frequency = ArhamFrequency.WEEKLY,
                        lastContactedAt = baseMillis - 20 * 86_400_000L,
                    ),
                ),
            )
        )
        val preferences = FakeUserPreferencesDataSource() // مفعّل افتراضياً (true)
        val planner = planner(repo)
        // الموعد المتوقع يُحسب من الخطة المشتركة نفسها التي يستشيرها المسار:
        val expectedFireAt = planner.plan(preferences.current).fireAtMillis
        val scheduler = RecordingScheduler()

        IosArhamReminderDispatch.rescheduleFromNow(
            preferences = preferences,
            planner = planner,
            scheduler = scheduler,
        )

        assertEquals(
            listOf(expectedFireAt),
            scheduler.scheduledAt,
            "جدولةٌ واحدة بلحظة الخطة الحقيقية من `now` الثابت — لا أكثر ولا أقل",
        )
        assertEquals(0, scheduler.cancelCount, "المفعّل لا يلمس الإلغاء إطلاقاً")
    }
}
