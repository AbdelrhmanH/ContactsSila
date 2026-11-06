package app.sila.arham.notification

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ImportantDate
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamNotificationContent
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.OccasionAlertKind
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNNotificationSound

/**
 * اختبارات بناء طلب إشعار التذكير (T8-12 · مراجعة مستقلة F-2): عقد **الاستبدال
 * بالمُعرّف الثابت** (ميزانية ≤ 1/يوم — مرآة اختبارات Robolectric أندرويد التي
 * تحقّق الاستبدال فوق AlarmManager الحقيقي)، والزنّاد اليومي المتكرر، ومحتوى
 * الحزمة من المُحلّ المحقون حصراً — والمحتوى نفسه من المخطط المشترك الحقيقي فوق
 * تزييفات حتمية (مرآة مُثبّتات `PlanArhamDailyNotificationUseCaseTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosReminderRequestTest {

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

    private fun contact(
        id: Long,
        displayName: String,
        lastContactedDaysAgo: Int? = null,
        dates: List<ImportantDate> = emptyList(),
        frequency: ArhamFrequency = ArhamFrequency.WEEKLY,
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = displayName),
        category = Category.FAMILY,
        dates = dates,
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = frequency,
            lastContactedAt = lastContactedDaysAgo?.let { baseMillis - it * 86_400_000L },
        ),
    )

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

    private fun scheduler(repo: FakeArhamRepository): IosArhamNotificationScheduler =
        IosArhamNotificationScheduler(
            planner = planner(repo),
            preferences = FakeUserPreferencesDataSource(),
            strings = MarkerStrings,
            timeZone = utc,
            centerProvider = { null }, // بناء الطلب لا يلمس المركز إطلاقاً — الخياطة تثبت ذلك
        )

    /**
     * مُحلّ نصوص مُرسَب — صيغه ثابتة تُثبت أن نصوص الطلب تأتي من المُحلّ المحقون حصراً
     * (موارد الحزمة لاحقاً تُختبر على المحاكي عبر مسار `IosBundleArhamNotificationStrings`).
     */
    private object MarkerStrings : IosArhamNotificationStrings {
        override fun digestTitle(): String = "عنوان_مرسَب"
        override fun digestBody(headlineDisplayName: String, additionalOverdueCount: Int): String =
            formatDigestBody(
                additionalOverdueCount = additionalOverdueCount,
                oneFormat = "واحد:%@",
                manyFormat = "كثيرون:%@ +%lld",
                displayName = headlineDisplayName,
            )

        override fun occasionBody(kind: OccasionAlertKind, type: String, displayName: String): String =
            formatOccasionBody(
                kind = kind,
                type = type,
                displayName = displayName,
                birthdayToday = "ميلاد_اليوم:%@",
                birthdayTomorrow = "ميلاد_الغد:%@",
                anniversaryToday = "ذكرى_اليوم:%@",
                anniversaryTomorrow = "ذكرى_الغد:%@",
            )

        override fun callNowActionTitle(): String = "فعل_اتصل"
        override fun remindTomorrowActionTitle(): String = "فعل_ذكّرني"
    }

    @Test
    fun reminderRequest_pinsFixedIdentifierAndDailyRepeatingTrigger() = runTest {
        val repo = FakeArhamRepository(listOf(contact(id = 1, displayName = "عم المتأخر", lastContactedDaysAgo = 20)))
        val plan = planner(repo).plan(prefs())
        val content = assertNotNull(plan.content)

        val request = scheduler(repo).reminderRequest(content = content, fireAtMillis = plan.fireAtMillis)

        assertEquals(
            IosArhamNotificationScheduler.REMINDER_IDENTIFIER,
            request.identifier,
            "المُعرّف الثابت هو عقد الاستبدال (الجدولة المتتابعة تستبدل ولا تتراكم)",
        )
        val trigger = assertIs<UNCalendarNotificationTrigger>(
            request.trigger,
            "الزنّاد تقويمي (خطة سطر 552)",
        )
        assertTrue(trigger.repeats, "الزنّاد يومي متكرر — لا إعادة جدولة نظامية تُختلق")
        assertEquals("عنوان_مرسَب", request.content.title, "العنوان من المُحلّ المحقون حصراً")
        assertNotNull(request.content.sound, "الصوت الافتراضي مضبوط (مرآة أندرويد)")
    }

    @Test
    fun reminderRequest_digestBody_goesThroughThePureSingularPluralDecision() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(id = 1, displayName = "عم المتأخر", lastContactedDaysAgo = 20),
                contact(id = 2, displayName = "عم آخر", lastContactedDaysAgo = 10),
            )
        )
        val content = assertNotNull(planner(repo).plan(prefs()).content)
        assertTrue(content is ArhamNotificationContent.Digest)

        val request = scheduler(repo).reminderRequest(content = content, fireAtMillis = baseMillis)
        assertEquals("واحد:عم المتأخر", request.content.body, "الرأس عنوان المتأخر الأشد إلحاحاً — والنص من صيغ المُرسَب عبر المُنبِّئ النقي")
    }

    @Test
    fun reminderRequest_occasionBody_usesTheOccasionKey() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(
                    id = 1,
                    displayName = "عم المناسبة",
                    dates = listOf(ImportantDate(type = "BIRTHDAY", month = 2, day = 27)),
                    frequency = ArhamFrequency.OCCASIONS,
                ),
            )
        )
        val content = assertNotNull(planner(repo).plan(prefs()).content)
        assertTrue(content is ArhamNotificationContent.Occasion, "عيد ميلاد اليوم ⇒ تنبيه مناسبة")

        val request = scheduler(repo).reminderRequest(content = content, fireAtMillis = baseMillis)
        assertEquals("ميلاد_اليوم:عم المناسبة", request.content.body, "جسد المناسبة من مفتاح اليوم الصحيح")
    }

    private fun prefs(
        hour: Int = 20,
        minute: Int = 0,
        quietStart: Int = 23,
        quietEnd: Int = 8,
        dayBefore: Boolean = false,
    ) = app.sila.core.model.UserPreferences(
        arhamNotificationHour = hour,
        arhamNotificationMinute = minute,
        quietHoursStartHour = quietStart,
        quietHoursEndHour = quietEnd,
        arhamOccasionDayBeforeEnabled = dayBefore,
    )
}
