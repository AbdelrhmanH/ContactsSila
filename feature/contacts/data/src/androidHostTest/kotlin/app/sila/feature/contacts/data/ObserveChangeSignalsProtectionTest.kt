package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ContactsChangeSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات حماية مراقبة إشارات التغيير على Android (DEF-01 · Issue #43) — تُنفَّذ ضد
 * [observeChangeSignalsFlow] نفسه (كود الإنتاج حرفياً) عبر بوابة بديلة تقع على حدّ
 * التسجيل ذاته: منطق الحماية (الحارس/الالتقاط الضيق/التوازن/التجميع) هو المُختبر،
 * بينما نداء `ContentResolver.registerContentObserver` البيني يستحيل تنفيذه على مضيف
 * JVM (android.jar Stub — لا Robolectric في المستودع) ويُثبته مسار الجهاز الفعلي في
 * قائمة QA الأجهزة — لا يُدَّعى هنا أن النداء الحقيقي نُفِّذ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveChangeSignalsProtectionTest {

    /** بديل البوابة — يقع على حدّ التسجيل نفسه ولا يلتقط أي نوع أندرويد. */
    private class FakeGateway : ContactsProviderAccessGateway {
        var readGranted: Boolean = true
        var registrationError: SecurityException? = null

        var registerCount: Int = 0
            private set
        var unregisterCount: Int = 0
            private set
        var activeCount: Int = 0
            private set
        private val listeners = mutableListOf<() -> Unit>()

        override fun isReadPermissionGranted(): Boolean = readGranted

        override fun registerChangeObserver(onChange: () -> Unit): AutoCloseable {
            registerCount++ // المحاولة تُحتسب حتى لو رفضها المزوّد (التقاط ضيق — A2)
            registrationError?.let { throw it }
            activeCount++
            listeners += onChange
            return AutoCloseable {
                unregisterCount++
                activeCount--
                listeners.remove(onChange)
            }
        }

        /** إطلاق تغيير دفتر اتصالات من «المنصة» (استدعاء المراقب المسجَّل). */
        fun fireChange() {
            listeners.toList().forEach { it() }
        }
    }

    @Test
    fun observeChangeSignals_denied_doesNotRegisterOrThrow() = runTest {
        val gateway = FakeGateway().apply { readGranted = false }
        val received = mutableListOf<ContactsChangeSignal>()

        val job = launch {
            observeChangeSignalsFlow(gateway, debounceMillis = 5_000L).collect { received += it }
        }
        runCurrent()
        gateway.fireChange()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, gateway.registerCount, "denied permission must never register an observer")
        assertEquals(0, received.size, "denied permission must never emit any event")
        assertEquals(0, gateway.activeCount)

        job.cancelAndJoin()
        assertEquals(0, gateway.unregisterCount, "no unregister for a registration that never happened")
    }

    @Test
    fun observeChangeSignals_granted_registersOnce_andDeliversDebouncedSignals() = runTest {
        val gateway = FakeGateway()
        val received = mutableListOf<ContactsChangeSignal>()

        val job = launch {
            observeChangeSignalsFlow(gateway, debounceMillis = 5_000L).collect { received += it }
        }
        runCurrent()

        assertEquals(1, gateway.registerCount, "granted permission registers the observer exactly once")

        gateway.fireChange()
        gateway.fireChange()
        gateway.fireChange()
        runCurrent()
        assertEquals(0, received.size, "no signal before the debounce window elapses")

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, received.size, "a burst of changes coalesces into one debounced signal (A4)")

        job.cancelAndJoin()
        assertEquals(1, gateway.unregisterCount, "cancellation unregisters exactly once (A3)")
        assertEquals(0, gateway.activeCount)
    }

    @Test
    fun observeChangeSignals_registrationSecurityException_isHandledNarrowly_andObservable() = runTest {
        val gateway = FakeGateway().apply {
            registrationError = SecurityException(
                "Permission Denial: opening provider com.android.providers.contacts.ContactsProvider2 " +
                    "requires android.permission.READ_CONTACTS or android.permission.WRITE_CONTACTS"
            )
        }
        val diagnostics = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(diagnostics, true, Charsets.UTF_8.name()))
        val received = mutableListOf<ContactsChangeSignal>()
        try {
            val job = launch {
                observeChangeSignalsFlow(gateway, debounceMillis = 5_000L).collect { received += it }
            }
            runCurrent()
            gateway.fireChange()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(1, gateway.registerCount, "the registration attempt itself happened (guard passed)")
            assertEquals(0, received.size, "a failed registration must not emit events or fake activity")
            assertTrue(
                diagnostics.toString(Charsets.UTF_8.name()).contains("observer registration denied"),
                "the failure must remain observable in diagnostics (A2) — captured: ${diagnostics}"
            )
            assertEquals(0, gateway.unregisterCount, "no unregister for a registration that never succeeded (A3)")

            job.cancelAndJoin()
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun observeChangeSignals_cancellation_unregisters() = runTest {
        val gateway = FakeGateway()

        val job = launch {
            observeChangeSignalsFlow(gateway, debounceMillis = 5_000L).collect { /* ignore */ }
        }
        runCurrent()
        assertEquals(1, gateway.activeCount)

        job.cancelAndJoin()

        assertEquals(1, gateway.unregisterCount, "awaitClose unregisters exactly once after successful registration")
        assertEquals(0, gateway.activeCount, "no leaked observer after collection ends")
    }

    @Test
    fun observeChangeSignals_reSubscriptions_areBalanced() = runTest {
        val gateway = FakeGateway()

        repeat(times = 3) {
            val job = launch {
                observeChangeSignalsFlow(gateway, debounceMillis = 5_000L).collect { /* ignore */ }
            }
            runCurrent()
            assertEquals(1, gateway.activeCount, "sequential subscription $it: exactly one active observer")
            job.cancelAndJoin()
            runCurrent()
            assertEquals(0, gateway.activeCount, "sequential subscription $it: cleanup completed")
        }

        assertEquals(3, gateway.registerCount, "three subscriptions → three registrations")
        assertEquals(3, gateway.unregisterCount, "three cancellations → three unregistrations (no accumulation)")
    }
}
