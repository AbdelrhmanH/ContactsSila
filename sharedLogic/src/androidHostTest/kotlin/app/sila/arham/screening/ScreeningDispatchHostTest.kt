package app.sila.arham.screening

import android.telecom.Call
import app.sila.core.model.CallEvent
import app.sila.core.model.CallSource
import app.sila.feature.arham.domain.CallEventIngestion
import app.sila.feature.arham.domain.ScreenedCallSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات مسار الفرز الحار (T8-09 · AC-5) — ترتيبُ «الاستجابة المحايدة قبل أي DB»
 * مثبَّت بمستقبلٍ صارم يسجّل كل فعل في قائمة مشتركة واحدة: الصادر يُصمَت تاماً،
 * الوارد يستجيب أولاً ثم يُدخل، والإشارة غير المقروءة والـ Koin الغائب يستجيبان
 * ويصمتان — بلا انهيار قط.
 */
class ScreeningDispatchHostTest {

    /** مستقبل صارم: يضيف «respond» إلى القائمة المشتركة بترتيبها الدقيق. */
    private class RecordingSink(private val actions: MutableList<String>) : ScreeningResponseSink {
        val responses = mutableListOf<ScreeningResponse>()
        override fun respond(response: ScreeningResponse) {
            actions += "respond"
            responses += response
        }
    }

    private val dispatch = ScreeningDispatch()
    private val incoming = ScreenedCallSignal(
        remoteNumber = "+201000000000",
        verificationPassed = false,
        timestampMillis = 1_000L,
    )
    private val persistedResult: CallEventIngestion = CallEventIngestion.Persisted(
        CallEvent(
            id = "screening-1000",
            primarySource = CallSource.ANDROID_CALL_SCREENING,
            evidenceSources = setOf(CallSource.ANDROID_CALL_SCREENING),
            timestamp = 1_000L,
        ),
    )

    /** إدخال مسجِّل — يضيف «ingest» بعد «respond» متى استُدعِي، ويعيد النتيجة المُمرَّرة. */
    private fun ingestRecording(actions: MutableList<String>, result: CallEventIngestion?): suspend (ScreenedCallSignal) -> CallEventIngestion? =
        { _ ->
            actions += "ingest"
            result
        }

    @Test
    fun outgoingDirection_totalSilence_noRespondNoIngest() = runTest {
        val actions = mutableListOf<String>()
        val sink = RecordingSink(actions)

        val result = dispatch.handleScreenedCall(
            callDirection = Call.Details.DIRECTION_OUTGOING,
            signal = incoming,
            sink = sink,
            ingest = ingestRecording(actions, persistedResult),
        )

        assertNull(result, "الصادر ⇒ لا نتيجة")
        assertTrue(actions.isEmpty(), "الصادر ⇒ لا استجابة ولا إدخال (قرار مثبت)")
        assertTrue(sink.responses.isEmpty())
    }

    @Test
    fun incomingDirection_respondsNeutralFirst_thenIngests_strictOrder() = runTest {
        val actions = mutableListOf<String>()
        val sink = RecordingSink(actions)

        val result = dispatch.handleScreenedCall(
            callDirection = Call.Details.DIRECTION_INCOMING,
            signal = incoming,
            sink = sink,
            ingest = ingestRecording(actions, persistedResult),
        )

        assertEquals(persistedResult, result)
        assertEquals(
            listOf("respond", "ingest"),
            actions,
            "الترتيب: استجابة محايدة قبل أي إدخال — AC-5، لا يُعاد ترتيبه",
        )
        assertEquals(ScreeningResponse.NEUTRAL, sink.responses.single(), "الاستجابة المحايدة حصراً — لا حجب ولا رفض")
    }

    @Test
    fun unreadableSignal_respondsNeutralButSkipsIngestion() = runTest {
        val actions = mutableListOf<String>()
        val sink = RecordingSink(actions)

        val result = dispatch.handleScreenedCall(
            callDirection = Call.Details.DIRECTION_INCOMING,
            signal = null,
            sink = sink,
            ingest = ingestRecording(actions, persistedResult),
        )

        assertNull(result)
        assertEquals(listOf("respond"), actions, "استجبنا للمكالمة ثم صمتنا — الإشارة غير مقروءة")
    }

    @Test
    fun koinUnavailableIngestReturningNull_stillRespondedNeutralFirst() = runTest {
        val actions = mutableListOf<String>()
        val sink = RecordingSink(actions)

        val result = dispatch.handleScreenedCall(
            callDirection = Call.Details.DIRECTION_INCOMING,
            signal = incoming,
            sink = sink,
            ingest = ingestRecording(actions, null), // Koin غير متاح ⇒ تخطٍّ هادئ (§O.5)
        )

        assertNull(result)
        assertEquals(
            listOf("respond", "ingest"),
            actions,
            "الاستجابة تمت قبل محاولة الإدخال دائماً — بغضّ النظر عن Koin",
        )
    }

    @Test
    fun unknownDirection_beforeApi28_treatedAsIncoming_conservatively() = runTest {
        val actions = mutableListOf<String>()
        val sink = RecordingSink(actions)

        val result = dispatch.handleScreenedCall(
            callDirection = null, // قبل API 28 — لا بدائية اتجاه
            signal = incoming,
            sink = sink,
            ingest = ingestRecording(actions, persistedResult),
        )

        assertEquals(persistedResult, result)
        assertEquals(listOf("respond", "ingest"), actions, "المجهول يُعامَل وارداً تحفّظاً — استجابة وإدخال")
    }
}
