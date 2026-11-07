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
import app.sila.core.model.KinshipLayer
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.core.model.RingingStatus
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.ArhamCelebrationPolicy
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.BuildArhamFamilyTreeUseCase
import app.sila.feature.arham.domain.CelebrateTransitionUseCase
import app.sila.feature.arham.domain.CallEventStateMachine
import app.sila.feature.arham.domain.ConfirmationAnswer
import app.sila.feature.arham.domain.EmergencyRingingController
import app.sila.feature.arham.domain.EmergencySessionRepository
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.ObserveArhamContactsUseCase
import app.sila.feature.arham.domain.ObservePendingConfirmationsUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import app.sila.feature.arham.domain.ResolveConfirmationUseCase
import app.sila.feature.arham.domain.StopEmergencyRingingUseCase
import app.sila.feature.arham.domain.UpdateKinshipUseCase
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * اختبارات ViewModel أرحام (T8-06/T8-11 · خطة §D.3-4/§D.3-5/§P-T8-11 · Issue #29):
 * - زر الاتصال يُصدر أثر الاتصال ويحفظ محاولة `IN_APP` حصراً بلا احتفال مباشراً.
 * - الكونفيتي يُطلق مرة واحدة في الفترة عند انتقال أحمر←أخضر (أول انبعاث يبذّر فقط).
 * - T8-11: موافقة الفحص (AC-4)، لافتة الطوارئ من Room SSOT (§N)، حفظ القرابة
 *   (AC-1 بلا لمس `lastContactedAt`)، ضمّ قرابة المناسبات (O-8)، حسم التأكيد (AC-7)،
 *   وورقة عقدة الشجرة (AC-13).
 *
 * بلا مكعبات مشتركة مع اختبارات النطاق — الواجهة تملك بدائلها الخاصة (فصل نطاق
 * الاختبار عن `EmergencyFakes.kt` الداخلية لاختبارات النطاق).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamViewModelTest {

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        val upserts = mutableListOf<Pair<Long, ArhamInfo>>()
        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
            upserts += contactId to info
        }
    }

    private class FakeArhamCallEventRepository : ArhamCallEventRepository {
        val events = linkedMapOf<String, CallEvent>()
        val lastContactedWrites = mutableListOf<Pair<Long, Long>>()
        private val pendingFlow = MutableStateFlow<List<CallEvent>>(emptyList())

        override fun observePendingConfirmations(): Flow<List<CallEvent>> = pendingFlow

        override fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> =
            MutableStateFlow(events.values.filter { it.timestamp in fromMillis..toMillis })

        override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]

        override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
            events.values.filter { it.timestamp in fromMillis..toMillis }

        override suspend fun upsertEvent(event: CallEvent) {
            events[event.id] = event
            pendingFlow.value = events.values.filter { it.confirmation == CallConfirmation.PENDING }
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

    /** بديل واجهة مستودع جلسات الطوارئ — نفس عقد T8-10 (المرصد = حالة الشريط). */
    private class FakeEmergencySessionRepository : EmergencySessionRepository {
        val sessions = linkedMapOf<Long, EmergencyRingingSession>()
        private val sessionFlow = MutableStateFlow<EmergencyRingingSession?>(null)
        private var nextId = 1L

        override suspend fun countSessionsStartedSince(cutoffMillis: Long): Int =
            sessions.values.count { it.startedAt >= cutoffMillis }

        override suspend fun findInProgressSession(): EmergencyRingingSession? =
            sessions.values.lastOrNull { it.status == RingingStatus.IN_PROGRESS }

        override fun observeInProgressSession(): Flow<EmergencyRingingSession?> = sessionFlow

        override suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long {
            // انحراف موثّق عن التنفيذ الملموس: هنا خيطٌ واحد بلا نقاط تعليق بين القراءة
            // والكتابة فالذرّية محققة فعلياً؛ الذرّية الحقيقية (get-or-create ذرّي)
            // مسؤولية `EmergencySessionRepositoryImpl` (إصلاح P1-2 · T8-10).
            findInProgressSession()?.let { return it.id }
            val id = nextId++
            val session = EmergencyRingingSession(
                id = id,
                contactId = contactId,
                contactName = "ج$contactId",
                phoneE164 = "+2000000000$contactId",
                attempts = 0,
                status = RingingStatus.IN_PROGRESS,
                startedAt = startedAtMillis,
            )
            sessions[id] = session
            sessionFlow.value = session
            return id
        }

        override suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int? {
            val current = sessions[sessionId] ?: return null
            if (current.status != RingingStatus.IN_PROGRESS) return null
            val updated = current.copy(attempts = current.attempts + 1, lastAttemptAt = atMillis)
            sessions[sessionId] = updated
            sessionFlow.value = updated
            return updated.attempts
        }

        override suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean {
            val current = sessions[sessionId] ?: return false
            if (current.status != RingingStatus.IN_PROGRESS) return false
            sessions[sessionId] = current.copy(status = status, lastAttemptAt = atMillis)
            if (sessionFlow.value?.id == sessionId) sessionFlow.value = null
            return true
        }

        override suspend fun getContactById(contactId: Long): Contact? = null
    }

    private class FakeEmergencyRingingController : EmergencyRingingController {
        val started = mutableListOf<Pair<Long, Long>>()
        val runningSessions = mutableSetOf<Long>()
        var stopCalls = 0
        override fun start(sessionId: Long, contactId: Long) {
            started += sessionId to contactId
            runningSessions += sessionId
        }
        override fun stop() {
            stopCalls++
            runningSessions.clear()
        }
        override fun isRunning(sessionId: Long): Boolean = sessionId in runningSessions
    }

    private class FakeConsentSource(var initial: Boolean?) : ArhamScreeningConsentSource {
        override fun isRoleHeld(): Boolean? = initial
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

    private fun contact(
        id: Long,
        daysAgo: Int? = null,
        phones: List<Phone> = emptyList(),
        dates: List<app.sila.core.model.ImportantDate> = emptyList(),
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = "ج$id"),
        phones = phones,
        dates = dates,
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = ArhamFrequency.WEEKLY,
            lastContactedAt = daysAgo?.let { baseMillis - it * 86_400_000L },
        ),
    )

    private fun viewModel(
        repo: FakeArhamRepository,
        eventRepo: FakeArhamCallEventRepository,
        policy: FakePolicy = FakePolicy(),
        sessionRepo: FakeEmergencySessionRepository = FakeEmergencySessionRepository(),
        controller: FakeEmergencyRingingController = FakeEmergencyRingingController(),
        consentSource: FakeConsentSource = FakeConsentSource(initial = null),
    ): ArhamViewModel {
        val stateMachine = CallEventStateMachine()
        return ArhamViewModel(
            getPriorityList = GetArhamPriorityListUseCase(repo, clock, unconfined),
            getDashboardStats = GetArhamDashboardStatsUseCase(
                arhamRepository = repo,
                callEventRepository = eventRepo,
                clock = clock,
                dispatchers = unconfined,
            ),
            recordDialAttempt = RecordDialAttemptUseCase(ingest = IngestCallEventUseCase(eventRepo)),
            celebrateTransition = CelebrateTransitionUseCase(policy = policy, clock = clock),
            buildTree = BuildArhamFamilyTreeUseCase(repository = repo, clock = clock, dispatchers = unconfined),
            observePendingConfirmations = ObservePendingConfirmationsUseCase(eventRepo),
            resolveConfirmation = ResolveConfirmationUseCase(repository = eventRepo, stateMachine = stateMachine),
            observeOccasions = ObserveUpcomingOccasionsUseCase(repository = repo, clock = clock, dispatchers = unconfined),
            observeContacts = ObserveArhamContactsUseCase(repository = repo),
            updateKinship = UpdateKinshipUseCase(repository = repo),
            stopEmergency = StopEmergencyRingingUseCase(
                repository = sessionRepo,
                controller = controller,
                clock = clock,
            ),
            emergencySessionRepository = sessionRepo,
            screeningConsentSource = consentSource,
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

    // ===== T8-06 — مسار الاتصال والاحتفال (محرَّفة من النسخة السابقة بنفس الدلالات) =====

    @Test
    fun recordCall_sendsDialEffectAndPersistsAttempt_neverCelebratesDirectly() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14))) // أحمر
        val eventRepo = FakeArhamCallEventRepository()
        val vm = viewModel(repo, eventRepo)

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
        val vm = viewModel(repo, FakeArhamCallEventRepository())

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
        val vm = viewModel(repo, FakeArhamCallEventRepository())

        val effects = mutableListOf<ArhamEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        repo.contactsFlow.value = listOf(contact(1, daysAgo = 1)) // أصفر←أخضر
        advanceUntilIdle()
        assertFalse(effects.any { it is ArhamEffect.ShowCelebrationConfetti })
        collector.cancel()
    }

    // ===== T8-11 — موافقة الفحص (AC-4) =====

    @Test
    fun screeningConsent_neededWhenRoleAbsent_effectOnRequest_grantedAfterRefresh_hiddenOnDismiss() = runTest {
        val consent = FakeConsentSource(initial = false) // منصة مطبقة والدور غير محمول
        val vm = viewModel(consentSource = consent, repo = FakeArhamRepository(emptyList()), eventRepo = FakeArhamCallEventRepository())
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        val effects = mutableListOf<ArhamEffect>()
        val effectCollector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        assertEquals(ScreeningConsentUi.NEEDED, states.last().screeningConsent)

        vm.onIntent(ArhamIntent.RequestScreeningConsent)
        advanceUntilIdle()
        assertTrue(effects.contains(ArhamEffect.RequestScreeningRole))

        // المنصة تُرجع النتيجة عبر RefreshScreeningConsent — نفس النسخة الوحيدة.
        consent.initial = true
        vm.onIntent(ArhamIntent.RefreshScreeningConsent)
        advanceUntilIdle()
        assertEquals(ScreeningConsentUi.GRANTED, states.last().screeningConsent)

        // التجاهل جلسيٌّ حصراً — لا كتابة تفضيلات.
        vm.onIntent(ArhamIntent.DismissScreeningConsent)
        advanceUntilIdle()
        assertEquals(ScreeningConsentUi.HIDDEN, states.last().screeningConsent)

        stateCollector.cancel()
        effectCollector.cancel()
    }

    @Test
    fun screeningConsent_nullPlatform_hidesCardEntirely() = runTest {
        val vm = viewModel( // `null` = iOS/قبل API 29 — البطاقة لا تُقترح أصلاً
            consentSource = FakeConsentSource(initial = null),
            repo = FakeArhamRepository(emptyList()),
            eventRepo = FakeArhamCallEventRepository(),
        )
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()

        assertEquals(ScreeningConsentUi.HIDDEN, states.last().screeningConsent)
        stateCollector.cancel()
    }

    // ===== T8-11 — لافتة الطوارئ (Room SSOT · §N) =====

    @Test
    fun emergencySession_flowsFromRoom_andStopFinishesViaUseCase() = runTest {
        val sessionRepo = FakeEmergencySessionRepository()
        val controller = FakeEmergencyRingingController()
        val vm = viewModel(
            repo = FakeArhamRepository(emptyList()),
            eventRepo = FakeArhamCallEventRepository(),
            sessionRepo = sessionRepo,
            controller = controller,
        )
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()
        assertEquals(null, states.last().emergencySession)

        // بدء الجلسة خارج الواجهة (عبر المضيف/المتلقي) — الشريط يقرأ Room حصراً.
        sessionRepo.insertInProgressSession(contactId = 9L, startedAtMillis = baseMillis)
        sessionRepo.recordAttempt(sessionId = 1L, atMillis = baseMillis + 1_000)
        advanceUntilIdle()

        val shown = states.last().emergencySession
        assertTrue(shown != null && shown.status == RingingStatus.IN_PROGRESS)
        assertEquals(1, shown.attempts)

        vm.onIntent(ArhamIntent.StopEmergency)
        advanceUntilIdle()

        // الحسم عبر use case — الشريط يختفي والمنفذ وقِف (idempotent §N.8).
        assertEquals(null, states.last().emergencySession)
        assertEquals(RingingStatus.STOPPED_BY_USER, sessionRepo.sessions.getValue(1L).status)
        assertEquals(1, controller.stopCalls)
        stateCollector.cancel()
    }

    // ===== T8-11 — ورقة القرابة (AC-1) =====

    @Test
    fun saveKinship_withoutFrequency_ignoredAndSheetStaysOpen() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 3)))
        val vm = viewModel(repo, FakeArhamCallEventRepository())
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()

        vm.onIntent(ArhamIntent.OpenKinshipEditor(contactId = 1L))
        advanceUntilIdle()
        assertTrue(states.last().kinshipEditor != null)

        vm.onIntent(
            ArhamIntent.SaveKinship(
                contactId = 1L,
                relation = Relation.BROTHER,
                frequency = null, // الاختيار إلزامي — لا اختراع
                parentContactId = null,
            ),
        )
        advanceUntilIdle()

        assertTrue(repo.upserts.isEmpty())
        assertTrue(states.last().kinshipEditor != null) // الورقة بقيت مفتوحة
        stateCollector.cancel()
    }

    @Test
    fun saveKinship_withFrequency_persistsLayerAndPreservesLastContacted_thenCloses() = runTest {
        val last = baseMillis - 5 * 86_400_000L
        val repo = FakeArhamRepository(
            listOf(
                Contact(
                    id = 1L,
                    systemContactId = "sys-1",
                    name = ContactName(displayName = "ج1"),
                    arham = ArhamInfo(
                        relation = Relation.UNCLE_PATERNAL,
                        frequency = ArhamFrequency.WEEKLY,
                        lastContactedAt = last,
                    ),
                ),
            ),
        )
        val vm = viewModel(repo, FakeArhamCallEventRepository())
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()

        vm.onIntent(ArhamIntent.OpenKinshipEditor(contactId = 1L))
        advanceUntilIdle()

        vm.onIntent(
            ArhamIntent.SaveKinship(
                contactId = 1L,
                relation = Relation.BROTHER,
                frequency = ArhamFrequency.WEEKLY,
                parentContactId = 2L,
                pauseForDays = 30,
            ),
        )
        advanceUntilIdle()

        val (contactId, info) = repo.upserts.single()
        assertEquals(1L, contactId)
        assertEquals(Relation.BROTHER, info.relation)
        assertEquals(KinshipLayer.SELF_BRANCH, info.layer) // O-7: الطبقة مشتقة حصراً
        assertEquals(ArhamFrequency.WEEKLY, info.frequency)
        assertEquals(2L, info.parentContactId)
        assertEquals(last, info.lastContactedAt) // AC-8: القرابة لا تلمس خطّ التواصل
        assertTrue(info.pausedUntil != null) // الإيقاف المؤقت: 30 يوماً
        assertEquals(null, states.last().kinshipEditor) // النجاح يغلق الورقة
        stateCollector.cancel()
    }

    // ===== T8-11 — المناسبات وضمّ القرابة (O-5/O-8) =====

    @Test
    fun occasions_joinRelationFromContacts_forTranslatedContext() = runTest {
        val today = Instant.fromEpochMilliseconds(baseMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val birthday = today + DatePeriod(days = 3) // داخل نافذة 7 أيام
        val repo = FakeArhamRepository(
            listOf(
                contact(
                    1,
                    daysAgo = 2,
                    dates = listOf(
                        app.sila.core.model.ImportantDate(
                            type = "BIRTHDAY",
                            month = birthday.monthNumber,
                            day = birthday.dayOfMonth,
                        ),
                    ),
                ),
            ),
        )
        val vm = viewModel(repo, FakeArhamCallEventRepository())
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()

        val occasion = states.last().occasions.single()
        assertEquals(1L, occasion.contactId)
        assertEquals("ج1", occasion.displayName)
        assertEquals(Relation.UNCLE_PATERNAL, occasion.relation) // O-8: سياق القرابة للنص
        assertEquals(3, occasion.daysUntil)
        assertFalse(occasion.isToday)
        stateCollector.cancel()
    }

    // ===== T8-11 — حسم التأكيد (AC-7) وورقة عقدة الشجرة (AC-13) =====

    @Test
    fun resolveConfirmation_contacted_updatesEventAndLastContacted() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14)))
        val eventRepo = FakeArhamCallEventRepository()
        val pending = CallEvent(
            id = "evt-1",
            primarySource = CallSource.IN_APP,
            evidenceSources = setOf(CallSource.IN_APP),
            timestamp = baseMillis - 60_000,
            remoteNumber = "+201000000000",
            contactId = 1L,
            direction = CallDirection.OUT,
            durationSec = 180,
            outcome = CallOutcome.ATTEMPTED,
            confirmation = CallConfirmation.PENDING,
        )
        eventRepo.upsertEvent(pending)
        val vm = viewModel(repo, eventRepo)
        advanceUntilIdle()

        vm.onIntent(ArhamIntent.ResolveConfirmation(eventId = "evt-1", answer = ConfirmationAnswer.CONTACTED))
        advanceUntilIdle()

        val resolved = eventRepo.events.getValue("evt-1")
        assertTrue(resolved.confirmation != CallConfirmation.PENDING) // البطاقة تُرفع بعد الحسم
        assertEquals(listOf(1L to (baseMillis - 60_000)), eventRepo.lastContactedWrites) // AC-8
    }

    @Test
    fun resolveConfirmation_nonContactedAnswers_neverWriteLastContacted() = runTest {
        val repo = FakeArhamRepository(listOf(contact(1, daysAgo = 14)))
        val eventRepo = FakeArhamCallEventRepository()

        fun pendingEvent(id: String) = CallEvent(
            id = id,
            primarySource = CallSource.IN_APP,
            evidenceSources = setOf(CallSource.IN_APP),
            timestamp = baseMillis - 60_000,
            remoteNumber = "+201000000000",
            contactId = 1L,
            direction = CallDirection.OUT,
            outcome = CallOutcome.ATTEMPTED,
            confirmation = CallConfirmation.PENDING,
        )
        // «لاحقاً»: بلا كتابة إطلاقاً — يبقى الحدث PENDING (عقد ADR-0006 §5).
        eventRepo.upsertEvent(pendingEvent("evt-later"))
        val vmLater = viewModel(repo, eventRepo)
        vmLater.onIntent(ArhamIntent.ResolveConfirmation(eventId = "evt-later", answer = ConfirmationAnswer.LATER))
        advanceUntilIdle()
        assertEquals(CallConfirmation.PENDING, eventRepo.events.getValue("evt-later").confirmation)
        assertTrue(eventRepo.lastContactedWrites.isEmpty())

        // «لم يرد»: يُحسم CONFIRMED/UNANSWERED — بلا تحديث أرحام (AC-8: الرفض/اللا-إكمال لا يحدّث).
        eventRepo.upsertEvent(pendingEvent("evt-no-answer"))
        vmLater.onIntent(ArhamIntent.ResolveConfirmation(eventId = "evt-no-answer", answer = ConfirmationAnswer.NO_ANSWER))
        advanceUntilIdle()
        assertEquals(CallConfirmation.CONFIRMED, eventRepo.events.getValue("evt-no-answer").confirmation)
        assertTrue(eventRepo.lastContactedWrites.isEmpty())

        // «غير متعلقة»: رفض نهائي — بلا تحديث أرحام مهما كانت الأدلة.
        eventRepo.upsertEvent(pendingEvent("evt-not-related"))
        vmLater.onIntent(ArhamIntent.ResolveConfirmation(eventId = "evt-not-related", answer = ConfirmationAnswer.NOT_RELATED))
        advanceUntilIdle()
        assertEquals(CallConfirmation.REJECTED, eventRepo.events.getValue("evt-not-related").confirmation)
        assertTrue(eventRepo.lastContactedWrites.isEmpty())
    }

    @Test
    fun treeNodeSheet_resolvesStatusFromTreeCache_andPhoneFromContacts() = runTest {
        val repo = FakeArhamRepository(
            listOf(
                contact(
                    1,
                    daysAgo = 14, // أحمر
                    phones = listOf(
                        Phone(raw = "+201111111111", e164 = "+201111111111", isPrimary = true),
                        Phone(raw = "+202222222222", e164 = "+202222222222", isPrimary = false),
                    ),
                ),
            ),
        )
        val vm = viewModel(repo, FakeArhamCallEventRepository())
        val states = mutableListOf<ArhamUiState>()
        val stateCollector = launch { vm.state.collect { states += it } }
        advanceUntilIdle()

        vm.onIntent(ArhamIntent.OpenTreeNodeSheet(contactId = 1L))
        advanceUntilIdle()

        val sheet = states.last().treeNodeSheet
        assertTrue(sheet != null)
        assertEquals(1L, sheet.contactId)
        assertEquals(Relation.UNCLE_PATERNAL, sheet.relation)
        assertEquals(app.sila.core.model.ArhamStatus.OVERDUE, sheet.status) // من الشجرة المشتقة
        assertEquals("+201111111111", sheet.phone) // الأساسي أولاً
        stateCollector.cancel()
    }
}
