package app.sila.feature.arham

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.AppResult
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamStatus
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallOutcome
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import app.sila.feature.arham.domain.RecordManualContactUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            val callEventRepo = ArhamCallEventRepositoryImpl(db)
            val getPriority = GetArhamPriorityListUseCase(repo, fixedClock)
            val getStats = GetArhamDashboardStatsUseCase(
                arhamRepository = repo,
                callEventRepository = callEventRepo,
                clock = fixedClock,
                dispatchers = testDispatchers,
            )

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

            // D.3-1: تجميع حقيقي فوق قاعدة AC-8 — المحاولة غير المؤهَّلة لا تُحتسب تواصلاً
            // (ليست size*2 ولا عدّ الحالات)؛ والبذر يكتب lastContactedAt بلا أحداث.
            val statsAfterAttempt = getStats().first()
            assertEquals(0, statsAfterAttempt.contactedRelatives)
            assertEquals(0, statsAfterAttempt.totalFamilyCalls)

            // تسجيل يدوي «تواصلت معه» بنفس الرقم والزمن يُدمج في محاولة الاتصال القائمة
            // (منع التكرار ADR-0006 §5) — الدمج يرفع الحدث إلى CONFIRMED/COMPLETED المؤهَّل
            // (قاعدة AC-8) فيُحتسب. (بلا رقم كان الإخفاق مغلقاً صمامَه — §G.3 UNRESOLVED).
            val manual = RecordManualContactUseCase(repository = callEventRepo)
            val manualResult = manual(
                eventId = "manual-1",
                contactId = before.first().contactId,
                timestampMillis = fixedClock.nowEpochMillis(),
                remoteNumber = "+201000000000",
            )
            assertTrue(manualResult is AppResult.Success)
            // الدمج في الحدث القائم حصراً — لا حدث ثانٍ (المعرف الأول محفوظ).
            val merged = db.getCallEventById("inapp-${before.first().contactId}-${fixedClock.nowEpochMillis()}")
            assertEquals(CallConfirmation.CONFIRMED, merged?.confirmation)
            assertEquals(CallOutcome.COMPLETED, merged?.outcome)
            val statsAfterManual = getStats().first()
            assertEquals(1, statsAfterManual.contactedRelatives)
            assertEquals(1, statsAfterManual.totalFamilyCalls)
        } finally {
            db.closeConnection()
        }
    }
}
