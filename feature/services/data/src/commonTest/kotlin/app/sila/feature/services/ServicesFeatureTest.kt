package app.sila.feature.services

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.services.data.ServicesRepositoryImpl
import app.sila.feature.services.domain.LookupServiceByPhoneUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ServicesFeatureTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    @Test
    fun lookupServiceByPhone_findsServiceByLocalOrE164Number() = runTest {
        // F-01: الإنتاج لا يبذر — اختبار التركيبة التجريبية يُبذّر صراحةً.
        val db = LocalSilaDatabase(dispatchers = testDispatchers, seedDemoData = true)
        try {
            val repo = ServicesRepositoryImpl(db)
            val lookup = LookupServiceByPhoneUseCase(repo)

            val found = lookup("01555556666")
            assertNotNull(found)
            assertEquals("srv_1", found.cloudId)
        } finally {
            db.closeConnection()
        }
    }
}
