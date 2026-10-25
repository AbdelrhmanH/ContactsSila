package app.sila.feature.main

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.AppLanguage
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Category
import app.sila.core.model.DefaultTab
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.contacts.data.ContactsRepositoryImpl
import app.sila.feature.services.data.ServicesRepositoryImpl
import app.sila.feature.settings.data.SettingsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

    @Test
    fun getArhamPriorityList_ordersOverdueFirst() = runTest {
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers)
        try {
            val arhamRepo = ArhamRepositoryImpl(db)
            val useCase = GetArhamPriorityListUseCase(arhamRepo, fixedClock)

            val items = useCase().first()
            assertEquals(3, items.size)
            assertEquals(1, items[0].rank)
            assertEquals("العم أحمد", items[0].displayName)
            assertEquals(ArhamStatus.OVERDUE, items[0].status)
        } finally {
            db.close()
        }
    }

    @Test
    fun callingOverdueRelative_transitionsStatusFromOverdueToOk() = runTest {
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers)
        try {
            val contactsRepo = ContactsRepositoryImpl(db)
            val arhamRepo = ArhamRepositoryImpl(db)
            val servicesRepo = ServicesRepositoryImpl(db)
            val settingsRepo = SettingsRepositoryImpl(SilaPreferencesDataSource())
            val getPriority = GetArhamPriorityListUseCase(arhamRepo, fixedClock)
            val getStats = GetArhamDashboardStatsUseCase(getPriority)

            val vm = MainViewModel(
                contactsRepository = contactsRepo,
                arhamRepository = arhamRepo,
                servicesRepository = servicesRepo,
                getArhamPriorityList = getPriority,
                getArhamStats = getStats,
                settingsRepository = settingsRepo,
                clock = fixedClock
            )

            assertEquals(1, vm.state.value.overdueArhamCount)
            vm.onIntent(MainIntent.CallNumber(phone = "+201022223333", contactId = 2L))
            assertEquals(0, vm.state.value.overdueArhamCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun selectingCategoryAndTogglingLanguage_updatesUiState() = runTest {
        val db = LocalSilaDatabase(dispatchers = unconfinedDispatchers)
        try {
            val contactsRepo = ContactsRepositoryImpl(db)
            val arhamRepo = ArhamRepositoryImpl(db)
            val servicesRepo = ServicesRepositoryImpl(db)
            val settingsRepo = SettingsRepositoryImpl(SilaPreferencesDataSource())
            val getPriority = GetArhamPriorityListUseCase(arhamRepo, fixedClock)
            val getStats = GetArhamDashboardStatsUseCase(getPriority)

            val vm = MainViewModel(
                contactsRepository = contactsRepo,
                arhamRepository = arhamRepo,
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
            db.close()
        }
    }
}
