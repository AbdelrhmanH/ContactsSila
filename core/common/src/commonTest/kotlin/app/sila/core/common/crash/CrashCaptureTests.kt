package app.sila.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات منظّم التقاط الأعطال المحلي (ADR-0019 — تنسيق v2 الصارم):
 *
 * 1. عقد fail-closed كاملاً — البوابة المغلقة/الفاشلة لا تكتب، وفشل المُركِّب
 *    أو Sink لا يرمي ولا يقطع مسار العطل النظامي.
 * 2. فحص **التقرير المُخلَّد كاملاً** (معيار #31): بحث كل سر اصطناعي (أسماء
 *    لاتينية وعربية، رسائل، أرقام كل المجموعات، بريد) في الملف المكتوب فلا سر
 *    ينجو من أي حقل مسموح — لا فحص دوال مساعدة منفردة فحسب.
 * 3. سقف الاحتفاظ (5 تقارير) عبر الخوارزمية النقية المشتركة.
 */
class CrashCaptureTests {

    // ============================================================
    // LocalCrashCapture — عقد fail-closed + فحص التقرير المُخلَّد كاملاً
    // ============================================================

    @Test
    fun capturePersistsNoSyntheticPiiAnywhereInFullReport() {
        val sink = RecordingSink(writeResult = true)
        val secretLatinName = "ZedQuixley"
        val secretArabicName = "أحمد عبد الرحمن"
        val secretPhone = "01012345678"
        val secretArabicPhone = "٠١٠١٢٣٤٥٦٧٨"
        val secretPersianPhone = "۰۹۱۲۳۴۵۶۷۸۹"
        val secretInternationalPhone = "+201001234567"
        val secretEmail = "zed.quixley@example.com"
        val throwable = IllegalStateException(
            "contact $secretLatinName / $secretArabicName failed — $secretPhone",
            RuntimeException(
                "cause: $secretEmail — $secretArabicPhone / $secretPersianPhone / $secretInternationalPhone",
            ),
        )

        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = sink,
        ).capture(timestampMillis = 42L, threadName = "main", throwable = throwable)

        assertTrue(written)
        assertEquals(1, sink.calls.size)
        val content = sink.calls.single().second
        // فحص التقرير الكامل: لا سر اصطناعي ينجو من أي حقل مسموح.
        listOf(
            secretLatinName,
            secretArabicName,
            secretPhone,
            secretArabicPhone,
            secretPersianPhone,
            secretInternationalPhone,
            secretEmail,
            "message:",
        ).forEach { secret ->
            assertFalse(content.contains(secret), "PII leaked into persisted report: $secret")
        }
        // والحقول البنيوية التشخيصية باقية.
        assertTrue(content.contains("format: 2"))
        assertTrue(content.contains("timestamp_epoch_ms: 42"))
        assertTrue(content.contains("thread: main"))
    }

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
    fun composerThrowingIsFailClosed() {
        val sink = RecordingSink(writeResult = true)

        val written = LocalCrashCapture(
            isCaptureEnabled = { true },
            sink = sink,
        ).capture(0L, "main", ThrowingCauseThrowable())

        assertFalse(written)
        assertEquals(0, sink.calls.size)
    }

    // ============================================================
    // الاحتفاظ — الخوارزمية النقية المشتركة (سقف 5 تقاريراً)
    // ============================================================

    @Test
    fun retentionKeepsAtMostMaxReportsDroppingOldest() {
        val seven = (1..7).map { index -> "crash-$index.txt" }

        assertEquals(
            listOf("crash-2.txt", "crash-1.txt"),
            staleReportNames(names = seven, max = 5),
            "الأقدم معجمياً (أصغر millis) هو الذي يُحذف — بالإرجاع التنازلي كما يستهلكه المستدعي",
        )
        assertEquals(emptyList<String>(), staleReportNames(names = (1..5).map { "crash-$it.txt" }, max = 5))
        assertEquals(emptyList<String>(), staleReportNames(names = listOf("crash-1.txt"), max = 5))
        assertEquals(emptyList<String>(), staleReportNames(names = emptyList(), max = 5))
    }
}

/** Throwable ترفع من cause — لإثبات أن فشل التركيب نفسه fail-closed لا رامي. */
private open class ThrowingCauseThrowable : Throwable() {
    override val cause: Throwable? get() = error("broken cause machinery")
}

/** Sink تسجيلي يلتقط الاستدعاءات للتحقق من المحتوى والعدد. */
private class RecordingSink(private val writeResult: Boolean) : CrashSink {
    val calls = mutableListOf<Pair<Long, String>>()

    override fun write(timestampMillis: Long, content: String): Boolean {
        calls += timestampMillis to content
        return writeResult
    }
}
