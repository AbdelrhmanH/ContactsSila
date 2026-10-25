package app.sila.feature

import app.sila.di.ensureKoinStarted
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.services.presentation.ServicesViewModel
import app.sila.feature.settings.presentation.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureViewModelsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun koinContainer_resolvesAllFeatureViewModelsAndSingletons() = runTest {
        val koin = ensureKoinStarted()

        val contactsVm = koin.get<ContactsViewModel>()
        val arhamVm = koin.get<ArhamViewModel>()
        val servicesVm = koin.get<ServicesViewModel>()
        val settingsVm = koin.get<SettingsViewModel>()

        assertNotNull(contactsVm)
        assertNotNull(arhamVm)
        assertNotNull(servicesVm)
        assertNotNull(settingsVm)

        val contactsState = contactsVm.state.first { it.contacts.isNotEmpty() }
        val arhamState = arhamVm.state.first { it.items.isNotEmpty() }
        val servicesState = servicesVm.state.first { it.services.isNotEmpty() }

        assertTrue(contactsState.contacts.isNotEmpty())
        assertTrue(arhamState.items.isNotEmpty())
        assertTrue(servicesState.services.isNotEmpty())
    }
}
