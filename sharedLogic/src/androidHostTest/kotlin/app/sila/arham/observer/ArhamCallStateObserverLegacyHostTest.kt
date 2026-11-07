package app.sila.arham.observer

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import app.sila.core.model.CallEvent
import app.sila.feature.arham.domain.CallEventStateMachine
import app.sila.core.model.CallOutcome
import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * فرع API 26–30 (`PhoneStateListener`) على شادو sdk 27 — نفس عقود الفرع الحديث:
 * التسجيل بالإذن، والانتقالات تُربَط بمحاولة صِلَة المفتوحة، وآخر تواصل لا يُلمس.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class ArhamCallStateObserverLegacyHostTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repo = ObserverTestRepository()
    private val clock = ObserverTestClock(nowMillis = 1_000_000L)

    @Test
    fun `granted permission registers listener and transitions correlate like the modern branch`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val attempt = CallEventStateMachine().classifyInAppDialIntent(
            eventId = "inapp-5-990000",
            remoteNumber = "+201000000000",
            timestampMillis = 990_000L,
            contactId = 5L,
        )
        repo.events[attempt.id] = attempt

        val observer = AndroidCallStateObserver(
            context = app,
            correlate = CorrelateObservedCallEndUseCase(repository = repo),
            clock = clock,
        )
        observer.start(CoroutineScope(Dispatchers.Unconfined))

        clock.nowMillis = 1_000_000L
        shadowOf(app.getSystemService(TelephonyManager::class.java))
            .setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        clock.nowMillis = 1_060_000L
        shadowOf(app.getSystemService(TelephonyManager::class.java))
            .setCallState(TelephonyManager.CALL_STATE_IDLE)

        assertEquals(CallOutcome.CONNECTED, repo.events.getValue(attempt.id).outcome, "الفرع القديم يربط كالحديث")
        assertTrue(repo.lastContactedWrites.isEmpty(), "AC-8: CONNECTED لا يُحدّث آخر تواصل")
        observer.stop()
    }
}
