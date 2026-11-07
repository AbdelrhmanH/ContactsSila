package app.sila.arham.observer

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import app.sila.core.common.AppClock
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallEvent
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.CallEventStateMachine
import app.sila.core.model.CallOutcome
import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import app.sila.core.model.CallSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** مستودع زائف مشترك لاختبارات المراقب (فرعا الـ API كلاهما). */
internal class ObserverTestRepository : ArhamCallEventRepository {
    val events = linkedMapOf<String, CallEvent>()
    val lastContactedWrites = mutableListOf<Pair<Long, Long>>()
    override fun observePendingConfirmations(): Flow<List<CallEvent>> =
        MutableStateFlow(events.values.filter { it.confirmation == CallConfirmation.PENDING })
    override fun observeEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>> =
        MutableStateFlow(events.values.filter { it.timestamp in fromMillis..toMillis })
    override suspend fun getEvent(eventId: String): CallEvent? = events[eventId]
    override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
        events.values.filter { it.timestamp in fromMillis..toMillis }
    override suspend fun upsertEvent(event: CallEvent) {
        events[event.id] = event
    }
    override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
        lastContactedWrites += contactId to timestampMillis
    }
}

/** ساعة قابلة للتحكم — لحظات الانتقالات تُثبَّت يدوياً لكل دورة. */
internal class ObserverTestClock(var nowMillis: Long) : AppClock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
}

/**
 * اختبارات مراقب حالة الهاتف على Robolectric — فرع API 31+ (`TelephonyCallback`،
 * sdk 34): بوابةُ الإذن حصراً (مرفوض ⇒ لا تسجيل ولا عملية)، والبداية idempotent،
 * والانتقالات تُربَط بمحاولة صِلَة المفتوحة — حالة فقط بلا أي هوية (AC-17).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArhamCallStateObserverHostTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repo = ObserverTestRepository()
    private val clock = ObserverTestClock(nowMillis = 1_000_000L)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun observer() = AndroidCallStateObserver(
        context = app,
        correlate = CorrelateObservedCallEndUseCase(repository = repo),
        clock = clock,
    )

    private fun telephony(): TelephonyManager = app.getSystemService(TelephonyManager::class.java)

    private fun seedOpenAttempt(startedAtMillis: Long): CallEvent {
        val attempt = CallEventStateMachine().classifyInAppDialIntent(
            eventId = "inapp-5-$startedAtMillis",
            remoteNumber = "+201000000000",
            timestampMillis = startedAtMillis,
            contactId = 5L,
        )
        repo.events[attempt.id] = attempt
        return attempt
    }

    // ===== بوابة الإذن (قرار مثبت: صلاحية فقط — بلا مفتاح DataStore) =====

    @Test
    fun `revoked READ_PHONE_STATE leaves observer unregistered without crashing`() {
        val observer = observer()
        observer.start(scope) // بلا منحٍ — الشادو لا يمنح افتراضياً
        assertFalse(observer.isRegistered, "مرفوض ⇒ لا تسجيل")
        // انتقالاتٌ مع ذلك لا تُحدث شيئاً ولا تسقط:
        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_IDLE)
        assertTrue(repo.events.isEmpty(), "بلا تسجيل ⇒ بلا ربط ⇒ بلا كتابة")
    }

    @Test
    fun `granted READ_PHONE_STATE registers the callback and stop unregisters`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val observer = observer()
        observer.start(scope)
        assertTrue(observer.isRegistered, "ممنوح ⇒ مسجَّل (فرع 31+ على sdk 34)")
        observer.stop()
        assertFalse(observer.isRegistered)
    }

    @Test
    fun `double start is idempotent - single registration lifecycle`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val observer = observer()
        observer.start(scope)
        observer.start(scope) // ثانية — بلا أثر
        assertTrue(observer.isRegistered)
        observer.stop()
        assertFalse(observer.isRegistered, "الإيقاف بعد بدايتين يفكّ مرة واحدة بأمان")
    }

    // ===== الإثراء النهائي عبر الانتقالات (حالة فقط — بلا هوية) =====

    @Test
    fun `offhook then idle enriches the single open in-app attempt`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val attempt = seedOpenAttempt(startedAtMillis = 990_000L)
        val observer = observer()
        observer.start(scope)

        clock.nowMillis = 1_000_000L
        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        clock.nowMillis = 1_060_000L
        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_IDLE)

        assertEquals(
            CallOutcome.CONNECTED,
            repo.events.getValue(attempt.id).outcome,
            "نهاية المرصود أثرت المحاولة المفتوحة الوحيدة (D-2 بالربط)",
        )
        assertTrue(
            repo.events.getValue(attempt.id).evidenceSources.contains(CallSource.ANDROID_TELEPHONY_OBSERVER),
            "اتحاد الدليل: المراقب شاهد النهاية",
        )
        assertTrue(repo.lastContactedWrites.isEmpty(), "AC-8: CONNECTED لا يُحدّث آخر تواصل")
        observer.stop()
    }

    @Test
    fun `ringing then idle without offhook writes nothing`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val observer = observer()
        observer.start(scope)

        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(telephony()).setCallState(TelephonyManager.CALL_STATE_IDLE)

        assertTrue(repo.events.isEmpty(), "مكالمة واردة لم تُجب — لا نهاية ولا كتابة")
        observer.stop()
    }
}
