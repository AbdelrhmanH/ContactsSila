package app.sila.feature.arham

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamStatus
import app.sila.core.model.CallOutcome
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
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
    fun arhamRepositoryAndUseCases_rankRelatives_dialAttemptNeverUpdatesLastContacted() = runTest {
        // T8-06 · خطة §D.3-4: مسار الاتصال أعيد نمذجته عبر آلة الحالات — المحاولة
        // (ATTEMPTED/PENDING) لا تحدّث آخر تواصل، فالترتيب والحالة يبقيان كما كانا (AC-3/AC-8).
        val db = LocalSilaDatabase(dispatchers = testDispatchers, seedDemoData = true)
        try {
            val repo = ArhamRepositoryImpl(db)
            val getPriority = GetArhamPriorityListUseCase(repo, fixedClock)
            val getStats = GetArhamDashboardStatsUseCase(getPriority)

            val before = getPriority().first()
            assertEquals(ArhamStatus.OVERDUE, before.first().status)

            val recordDial = RecordDialAttemptUseCase(
                ingest = IngestCallEventUseCase(ArhamCallEventRepositoryImpl(db)),
            )
            recordDial(
                contactId = before.first().contactId,
                remoteNumber = "+201000000000",
                timestampMillis = fixedClock.nowEpochMillis(),
            )

            val after = getPriority().first()
            assertEquals(before.first().status, after.first().status) // المحاولة لا تُغيّر الحالة

            // الحدث مُخزَّن محاولةً PENDING حصراً — دليل الاتصال متاح للاستعراض (AC-7) بلا اختلاق.
            val event = db.getCallEventById("inapp-${before.first().contactId}-${fixedClock.nowEpochMillis()}")
            assertEquals(CallOutcome.ATTEMPTED, event?.outcome)

            val stats = getStats().first()
            // التغيير المتعمد عن السلوك القديم (كان 2 عبر الكتابة غير المشروطة المحذوفة):
            // البذر فيه متواصلٌ واحد، والمحاولة لا تُحتسب تواصلاً (قاعدة AC-8 — خطة §D.3-4).
            assertEquals(1, stats.contactedRelatives)
        } finally {
            db.closeConnection()
        }
    }
}
