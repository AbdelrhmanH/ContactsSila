package app.sila.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات التقاط الأعطال المحلي (ADR-0019): عقد fail-closed كاملاً — البوابة
 * المغلقة/الفاشلة لا تكتب، وفشل Sink لا يرمي، والتنقية (Redactor) تسبق الكتابة.
 */
class CrashCaptureTests {

    // ============================================================
    // CrashReportComposer — التركيب والتنقية وسلسلة الأسباب
    // ============================================================

    @Test
    fun composeRedactsPhoneAndEmailFromMessageAndStack() {
        val throwable = IllegalStateException(
            "failed to sync contact user@example.com at +20 100 123 4567",
        )

        val report = CrashReportComposer.compose(
            timestampMillis = 1_700_000_000_000L,
            threadName = "main",
            throwable = throwable,
        )

        assertFalse(report.contains("user@example.com"))
        assertFalse(report.contains("+20 100 123 4567"))
        assertTrue(report.contains("[REDACTED_EMAIL]"))
        assertTrue(report.contains("[REDACTED_PHONE]"))
    }

    @Test
    fun composeIncludesHeaderTimestampThreadAndType() {
        val report = CrashReportComposer.compose(
            timestampMillis = 1_700_000_000_000L,
            threadName = "arham-dispatch",
            throwable = IllegalArgumentException("boom"),
        )

        assertTrue(report.startsWith("SILA LOCAL CRASH REPORT"))
        assertTrue(report.contains("timestamp_epoch_ms: 1700000000000"))
        assertTrue(report.contains("thread: arham-dispatch"))
        // بلا بادئة المنصة (java./kotlin. تختلف بين JVM وK/N).
        assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("message: boom"))
        assertTrue(report.contains("stack:"))
        assertTrue(report.contains("\tat "))
    }

    @Test
    fun composeWalksCauseChainUpToMaxDepth() {
        var throwable: Throwable = IllegalStateException("root-cause")
        repeat(times = 8) { index ->
            throwable = RuntimeException("wrapper-$index", throwable)
        }

        val report = CrashReportComposer.compose(0L, "main", throwable)

        // 8 غلافين فوق الجذر (9 عقد) ⇒ السلسلة تُقتطع عند MAX_CAUSE_DEPTH: تُطبع
        // العقد حتى wrapper-2، وأعمق من ذلك (wrapper-1/wrapper-0/root) يُقتطع حصراً.
        val causeMarkers = report.split("caused by:").size - 1
        assertEquals(CrashReportComposer.MAX_CAUSE_DEPTH, causeMarkers)
        assertTrue(report.contains("wrapper-4"))
        assertFalse(report.contains("root-cause"))
        assertFalse(report.contains("wrapper-0"))
    }

    @Test
    fun composeRedactsSelfReferencingCauseSafely() {
        val throwable = IllegalStateException("self-loop")
        // Kotlin Throwable.cause يعيد null عند cause === this — التأكد من عدم الدوران.
        val report = CrashReportComposer.compose(0L, "main", throwable)

        assertTrue(report.contains("self-loop"))
        assertEquals(0, report.split("caused by:").size - 1)
    }

    // ============================================================
    // LocalCrashCapture — عقد fail-closed
    // ============================================================

    @Test
    fun closedGateWritesNothing() {
        val sink = RecordingSink(writeResult = true)

        val written = LocalCrashCapture(
            isCaptureEnabled = { false },
            sink = sink,
        ).capture(0L, "main", IllegalStateException("ignored"))

        assertFalse(written)
        assertEquals(0, sink.calls.size)
    }

    @Test
    fun gateReadFailureIsFailClosed() {
        val sink = RecordingSink(writeResult = true)

        val written = LocalCrashCapture(
            isCaptureEnabled = { error("DataStore unavailable") },
            sink = sink,
        ).capture(0L, "main", IllegalStateException("ignored"))

        assertFalse(written)
        assertEquals(0, sink.calls.size)
    }

    @Test
    fun sinkThrowingIsSwallowedAndReturnsFalse() {
        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = CrashSink { _, _ -> error("disk full") },
        ).capture(0L, "main", IllegalStateException("boom"))

        assertFalse(written)
    }

    @Test
    fun sinkReturningFalsePropagatesFalse() {
        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = CrashSink { _, _ -> false },
        ).capture(0L, "main", IllegalStateException("boom"))

        assertFalse(written)
    }

    @Test
    fun openGateWritesScrubbedContent() {
        val sink = RecordingSink(writeResult = true)

        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = sink,
        ).capture(
            timestampMillis = 42L,
            threadName = "main",
            throwable = IllegalStateException("email user@example.com leaked"),
        )

        assertTrue(written)
        assertEquals(1, sink.calls.size)
        val (timestamp, content) = sink.calls.single()
        assertEquals(42L, timestamp)
        assertTrue(content.contains("[REDACTED_EMAIL]"))
        assertFalse(content.contains("user@example.com"))
    }

    @Test
    fun composerThrowingIsFailClosed() {
        val sink = RecordingSink(writeResult = true)

        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = sink,
        ).capture(0L, "main", ThrowingMessageThrowable())

        assertFalse(written)
        assertEquals(0, sink.calls.size)
    }

    /** Throwable ترفع message — لإثبات أن فشل التركيب نفسه fail-closed لا رامي. */
    private class ThrowingMessageThrowable : Throwable() {
        override val message: String? get() = error("broken message machinery")
    }

    /** Sink تسجيلي يلتقط الاستدعاءات للتحقق من المحتوى والعدد. */
    private class RecordingSink(private val writeResult: Boolean) : CrashSink {
        val calls = mutableListOf<Pair<Long, String>>()

        override fun write(timestampMillis: Long, content: String): Boolean {
            calls += timestampMillis to content
            return writeResult
        }
    }
}
