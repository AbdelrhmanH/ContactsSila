package app.sila.feature.main

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.AppLanguage
import app.sila.core.model.ArhamStatus
import app.sila.core.model.CallOutcome
import app.sila.core.model.Category
import app.sila.core.model.DefaultTab
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import app.sila.feature.contacts.data.ContactsRepositoryImpl
import app.sila.feature.services.data.ServicesRepositoryImpl
import app.sila.feature.settings.data.SettingsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val unconfinedDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }
    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_760_000_000_000L)
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recordDialAttempt(db: LocalSilaDatabase): RecordDialAttemptUseCase =
        RecordDialAttemptUseCase(ingest = IngestCallEventUseCase(ArhamCallEventRepositoryImpl(db)))

    @Test
    fun getArhamPriorityList_ordersOverdueFirst() = runTest {
        // F-01: الإنتاج لا يبذر — اختبارات التركيبة التجريبية تُبذّر صراحةً.
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers, seedDemoData = true)
        try {
            val arhamRepo = ArhamRepositoryImpl(db)
            val useCase = GetArhamPriorityListUseCase(arhamRepo, fixedClock)

            val items = useCase().first()
            assertEquals(3, items.size)
            assertEquals(1, items[0].rank)
            assertEquals("العم أحمد", items[0].displayName)
            assertEquals(ArhamStatus.OVERDUE, items[0].status)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun callNumber_recordsInAppAttempt_only_neverUpdatesLastContacted() = runTest {
        // T8-06 · خطة §D.3-4/AC-3/AC-8: الاتصال من داخل صِلَة يسجّل محاولة IN_APP حصراً —
        // الاختبار المتعمد المعاكس للسلوك القديم: بُعدُه عن الآلة كان يكتب آخر تواصل بلا بوابة.
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers, seedDemoData = true)
        var vm: MainViewModel? = null
        try {
            val contactsRepo = ContactsRepositoryImpl(db)
            val servicesRepo = ServicesRepositoryImpl(db)
            val settingsRepo = SettingsRepositoryImpl(SilaPreferencesDataSource())
            val getPriority = GetArhamPriorityListUseCase(ArhamRepositoryImpl(db), fixedClock, unconfinedDispatchers)
            val getStats = GetArhamDashboardStatsUseCase(
                arhamRepository = ArhamRepositoryImpl(db),
                callEventRepository = ArhamCallEventRepositoryImpl(db),
                clock = fixedClock,
                dispatchers = unconfinedDispatchers,
            )

            vm = MainViewModel(
                contactsRepository = contactsRepo,
                recordDialAttempt = recordDialAttempt(db),
                servicesRepository = servicesRepo,
                getArhamPriorityList = getPriority,
                getArhamStats = getStats,
                settingsRepository = settingsRepo,
                clock = fixedClock
            )

            assertEquals(1, vm.state.value.overdueArhamCount)
            vm.onIntent(MainIntent.CallNumber(phone = "+201022223333", contactId = 2L))

            // المحاولة لا تُحدّث آخر تواصل — عداد الأحمر ثابت (قاعدة AC-8 في آلة الحالات).
            assertEquals(1, vm.state.value.overdueArhamCount)

            // والحدث المُدخل محاولة PENDING حصراً بمعرف حتمي.
            val event = db.getCallEventById("inapp-2-${fixedClock.nowEpochMillis()}")
            assertEquals(CallOutcome.ATTEMPTED, event?.outcome)
        } finally {
            // نطاق الـ VM ملك له: يُلغى قبل إغلاق القاعدة (نمط ContactsViewModelKoinTest)
            // — جامعات Room الحية لا تلمس قاعدة مغلقة (تكامل دورة حياة الموارد).
            vm?.let { currentVm ->
                runBlocking {
                    withTimeout(5_000) {
                        runCatching {
                            currentVm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                        }
                    }
                }
            }
            db.closeConnection()
        }
    }

    @Test
    fun callNumberWithoutContact_dialsWithoutPersistingEvent() = runTest {
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers, seedDemoData = true)
        var vm: MainViewModel? = null
        try {
            vm = MainViewModel(
                contactsRepository = ContactsRepositoryImpl(db),
                recordDialAttempt = recordDialAttempt(db),
                servicesRepository = ServicesRepositoryImpl(db),
                getArhamPriorityList = GetArhamPriorityListUseCase(ArhamRepositoryImpl(db), fixedClock, unconfinedDispatchers),
                getArhamStats = GetArhamDashboardStatsUseCase(
                    arhamRepository = ArhamRepositoryImpl(db),
                    callEventRepository = ArhamCallEventRepositoryImpl(db),
                    clock = fixedClock,
                    dispatchers = unconfinedDispatchers,
                ),
                settingsRepository = SettingsRepositoryImpl(SilaPreferencesDataSource()),
                clock = fixedClock
            )

            vm.onIntent(MainIntent.CallNumber(phone = "+201111111111", contactId = null))
            assertEquals("+201111111111", vm.state.value.lastDialedPhone)
            assertTrue(db.getCallEventById("inapp-0-${fixedClock.nowEpochMillis()}") == null)
        } finally {
            // نطاق الـ VM ملك له: يُلغى قبل إغلاق القاعدة (نمط ContactsViewModelKoinTest)
            // — جامعات Room الحية لا تلمس قاعدة مغلقة (تكامل دورة حياة الموارد).
            vm?.let { currentVm ->
                runBlocking {
                    withTimeout(5_000) {
                        runCatching {
                            currentVm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                        }
                    }
                }
            }
            db.closeConnection()
        }
    }

    @Test
    fun selectingCategoryAndTogglingLanguage_updatesUiState() = runTest {
        // F-01: الإنتاج لا يبذر — البذر الصريح يجعل تصفية FAMILY تأكيداً غير فارغ (كان يمر فراغاً).
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers, seedDemoData = true)
        var vm: MainViewModel? = null
        try {
            val contactsRepo = ContactsRepositoryImpl(db)
            val servicesRepo = ServicesRepositoryImpl(db)
            val settingsRepo = SettingsRepositoryImpl(SilaPreferencesDataSource())
            val getPriority = GetArhamPriorityListUseCase(ArhamRepositoryImpl(db), fixedClock, unconfinedDispatchers)
            val getStats = GetArhamDashboardStatsUseCase(
                arhamRepository = ArhamRepositoryImpl(db),
                callEventRepository = ArhamCallEventRepositoryImpl(db),
                clock = fixedClock,
                dispatchers = unconfinedDispatchers,
            )

            vm = MainViewModel(
                contactsRepository = contactsRepo,
                recordDialAttempt = recordDialAttempt(db),
                servicesRepository = servicesRepo,
                getArhamPriorityList = getPriority,
                getArhamStats = getStats,
                settingsRepository = settingsRepo,
                clock = fixedClock
            )

            vm.onIntent(MainIntent.SelectTab(DefaultTab.ARHAM))
            assertEquals(DefaultTab.ARHAM, vm.state.value.activeTab)

            vm.onIntent(MainIntent.SelectCategory(Category.FAMILY))
            assertTrue(vm.state.value.contacts.all { it.category == Category.FAMILY })

            vm.onIntent(MainIntent.ToggleLanguage)
            assertEquals(AppLanguage.ENGLISH, vm.state.value.preferences.language)
        } finally {
            // نطاق الـ VM ملك له: يُلغى قبل إغلاق القاعدة (نمط ContactsViewModelKoinTest)
            // — جامعات Room الحية لا تلمس قاعدة مغلقة (تكامل دورة حياة الموارد).
            vm?.let { currentVm ->
                runBlocking {
                    withTimeout(5_000) {
                        runCatching {
                            currentVm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                        }
                    }
                }
            }
            db.closeConnection()
        }
    }
}
