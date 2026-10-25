package app.sila.feature.services

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.feature.services.data.ServicesRepositoryImpl
import app.sila.feature.services.domain.LookupServiceByPhoneUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServicesFeatureTest {

    @Test
    fun lookupServiceByPhone_findsServiceByLocalOrE164Number() = runTest {
        val db = LocalSilaDatabase()
        val repo = ServicesRepositoryImpl(db)
        val lookup = LookupServiceByPhoneUseCase(repo)

        val found = lookup("01555556666")
        assertNotNull(found)
        assertEquals("srv_1", found.cloudId)
    }
}
