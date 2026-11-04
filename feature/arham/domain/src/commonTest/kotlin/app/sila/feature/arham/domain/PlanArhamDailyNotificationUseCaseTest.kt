package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ImportantDate
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.OccasionAlertKind.DAY_BEFORE
import app.sila.feature.arham.domain.OccasionAlertKind.ON_DAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * اختبارات تخطيط الإشعار اليومي (T8-08 · §M · D2): موعد الإطلاق (الوقت المفضّل
 * اليوم/غداً ثم إزاحة ساعات الهدوء بقيم مُثبَّتة)، وأسبقية المناسبة على الرسالة
 * المجمعة، وراية «اليوم السابق»، وعنوان الرسالة المجمعة من المتأخر الأكثر إلحاحاً،
 * ولا محتوى بلا متأخر ولا مناسبة. كل شيء فوق ساعة ثابتة ومنطقة UTC حتمية.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanArhamDailyNotificationUseCaseTest {

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

    // ===== موعد الإطلاق =====

    @Test
    fun `preferred time today is honored when still future`() = runTest {
        val plan = planner(FakeArhamRepository(emptyList())).plan(prefs(hour = 20))
        assertEquals(Instant.parse("2026-02-27T20:00:00Z").toEpochMilliseconds(), plan.fireAtMillis)
    }

    @Test
    fun `past preferred time rolls to tomorrow`() = runTest {
        val plan = planner(FakeArhamRepository(emptyList())).plan(prefs(hour = 8, minute = 30))
        assertEquals(Instant.parse("2026-02-28T08:30:00Z").toEpochMilliseconds(), plan.fireAtMillis)
    }

    @Test
    fun `quiet hours defer today's slot to wake moment`() = runTest {
        // الوقت المفضل 20:00 داخل هدوء 19→21 (نهاري) ⇒ يُؤجَّل إلى 21:00 اليوم نفسه.
        val plan = planner(FakeArhamRepository(emptyList())).plan(prefs(hour = 20, quietStart = 19, quietEnd = 21))
        assertEquals(Instant.parse("2026-02-27T21:00:00Z").toEpochMilliseconds(), plan.fireAtMillis)
    }

    @Test
    fun `quiet hours defer tomorrow's slot across midnight window`() = runTest {
        // الوقت المفضل 07:30 غداً داخل الجانب الصباحي لهدوء 23→8 ⇒ غداً 08:00.
        val plan = planner(FakeArhamRepository(emptyList())).plan(prefs(hour = 7, minute = 30))
        assertEquals(Instant.parse("2026-02-28T08:00:00Z").toEpochMilliseconds(), plan.fireAtMillis)
    }

    // ===== المحتوى: أسبقية المناسبة ثم الرسالة المجمعة ثم لا شيء =====

    @Test
    fun `on-day occasion overrides digest`() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(id = 1, displayName = "عم المتأخر", lastContactedDaysAgo = 20),
                contact(
                    id = 2,
                    displayName = "عم المناسبة",
                    dates = listOf(ImportantDate(type = "BIRTHDAY", month = 2, day = 27)),
                    frequency = ArhamFrequency.OCCASIONS,
                ),
            )
        )
        val content = planner(repo).plan(prefs()).content
        val occasion = assertNotNull(content, "المناسبة الحساسة للوقت أولوية على الرسالة المجمعة")
        assertTrue(occasion is ArhamNotificationContent.Occasion)
        assertEquals(ON_DAY, occasion.kind)
        assertEquals("BIRTHDAY", occasion.type)
        assertEquals("عم المناسبة", occasion.displayName)
    }

    @Test
    fun `day-before alert surfaces only when flag enabled`() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(id = 1, displayName = "عم المتأخر", lastContactedDaysAgo = 20),
                contact(
                    id = 2,
                    displayName = "عم المناسبة",
                    dates = listOf(ImportantDate(type = "BIRTHDAY", month = 2, day = 28)),
                    frequency = ArhamFrequency.OCCASIONS,
                ),
            )
        )

        val withFlag = planner(repo).plan(prefs(dayBefore = true)).content
        assertTrue(withFlag is ArhamNotificationContent.Occasion, "الراية مفعّلة ⇒ تنبيه الغد يظهر")
        assertEquals(DAY_BEFORE, (withFlag as ArhamNotificationContent.Occasion).kind)
        assertEquals("عم المناسبة", withFlag.displayName)

        // الراية معطلة (الافتراضي) ⇒ المناسبة الغدية لا تُختلق ويظهر المتأخر:
        val withoutFlag = planner(repo).plan(prefs(dayBefore = false)).content
        val digest = assertNotNull(withoutFlag, "المتأخر موجود ⇒ رسالة مجمعة بدل الإهمال")
        assertTrue(digest is ArhamNotificationContent.Digest)
        assertEquals("عم المتأخر", digest.headlineDisplayName)
    }

    @Test
    fun `digest headline is most urgent overdue with additional count`() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(id = 1, displayName = "عم المتأخر قليلاً", lastContactedDaysAgo = 10),
                contact(id = 2, displayName = "عم الأكثر تأخراً", lastContactedDaysAgo = 20),
            )
        )
        val content = planner(repo).plan(prefs()).content
        val digest = assertNotNull(content, "متأخرون ⇒ رسالة مجمعة")
        assertTrue(digest is ArhamNotificationContent.Digest)
        // الإلحاح = الأيام منذ التواصل ÷ التكرار — 20 يوماً أشد إلحاحاً من 10 (O-9):
        assertEquals("عم الأكثر تأخراً", digest.headlineDisplayName)
        assertEquals(1, digest.additionalOverdueCount)
    }

    @Test
    fun `zero overdue and no occasions yields null content`() = runTest {
        val plan = planner(FakeArhamRepository(emptyList())).plan(prefs())
        assertNull(plan.content, "لا تُختلق حالة إلحاح (درس D.3)")
        assertTrue(plan.fireAtMillis > baseMillis, "ومع ذلك تُبقى جدولة الموعد القادم")
    }

    @Test
    fun `non-overdue contacts do not fabricate urgency`() = runTest {
        val repo = FakeArhamRepository(
            listOf(contact(id = 1, displayName = "عم حديث التواصل", lastContactedDaysAgo = 1))
        )
        assertNull(planner(repo).plan(prefs()).content, "urgency 1/7 أخضر — لا رسالة مجمعة")
    }
}
