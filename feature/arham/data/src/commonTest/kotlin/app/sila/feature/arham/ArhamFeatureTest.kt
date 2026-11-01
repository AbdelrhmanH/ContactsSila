package app.sila.feature.arham

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamStatus
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ArhamFeatureTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_760_000_000_000L)
    }

    @Test
    fun arhamRepositoryAndUseCases_rankRelativesAndUpdateOnCall() = runTest {
        // F-01: الإنتاج لا يبذر — اختبار التركيبة التجريبية يُبذّر صراحةً.
        val db = LocalSilaDatabase(dispatchers = testDispatchers, seedDemoData = true)
        try {
            val repo = ArhamRepositoryImpl(db)
            val getPriority = GetArhamPriorityListUseCase(repo, fixedClock)
            val getStats = GetArhamDashboardStatsUseCase(getPriority)

            val before = getPriority().first()
            assertEquals(ArhamStatus.OVERDUE, before.first().status)

            repo.recordContactCall(contactId = before.first().contactId, timestampMillis = fixedClock.nowEpochMillis())

            val after = getPriority().first()
            assertEquals(0, after.count { it.status == ArhamStatus.OVERDUE })
            val stats = getStats().first()
            assertEquals(2, stats.contactedRelatives)
        } finally {
            db.closeConnection()
        }
    }
}
