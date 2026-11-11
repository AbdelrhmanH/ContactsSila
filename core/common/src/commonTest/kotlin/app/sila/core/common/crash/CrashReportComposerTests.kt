package app.sila.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات تركيب تقرير العطل (ADR-0019 — تنسيق v2 الصارم): عقد الخصوصية
 * الصارم (معيار #31) — **لا نص حر في التقرير إطلاقاً**: الأسماء (لاتينية
 * وعربية) والنص الحر ورسائل الأسباب محذوفة حذفاً كلياً، والحقول البنيوية
 * المسموحة (الترويسة/thread/type/category/stack) باقية للتشخيص.
 */
class CrashReportComposerTests {

    @Test
    fun composeOmitsAllFreeTextIncludingNamesAndMessages() {
        val secretLatinName = "ZedQuixley"
        val secretArabicName = "أحمد عبد الرحمن"
        val secretArabicFreeText = "فشل تتبع المكالمة الأخيرة للجهة"
        val throwable = IllegalStateException(
            "contact $secretLatinName / $secretArabicName / $secretArabicFreeText",
        )

        val report = CrashReportComposer.compose(1_700_000_000_000L, "main", throwable)

        // لا رسائل إطلاقاً في التنسيق v2 — النص الحر محذوف حذفاً كلياً لا منقّى.
        assertFalse(report.contains("message:"), "format v2 must not emit message lines")
        assertFalse(report.contains(secretLatinName))
        assertFalse(report.contains(secretArabicName))
        assertFalse(report.contains(secretArabicFreeText))
        // الحقول البنيوية باقية للتشخيص.
        assertTrue(report.contains("type: "))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("stack:"))
    }

    @Test
    fun composeIncludesHeaderFormatTimestampThreadAndType() {
        val report = CrashReportComposer.compose(
            timestampMillis = 1_700_000_000_000L,
            threadName = "arham-dispatch",
            throwable = IllegalArgumentException("boom"),
        )

        assertTrue(report.startsWith("SILA LOCAL CRASH REPORT"))
        assertTrue(report.contains("format: 2"))
        assertTrue(report.contains("timestamp_epoch_ms: 1700000000000"))
        assertTrue(report.contains("thread: arham-dispatch"))
        // بادئة المنصة (java./kotlin.) تختلف بين JVM وK/N — البحث بالاسم البسيط.
        assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("stack:"))
        assertTrue(report.contains("\tat "))
    }

    @Test
    fun composeDropsNonStructuralThreadNameEntirely() {
        val report = CrashReportComposer.compose(
            timestampMillis = 0L,
            threadName = "thread-أحمد",
            throwable = IllegalStateException("boom"),
        )

        assertFalse(report.contains("thread:"), "non-structural thread name must be dropped whole")
    }

    @Test
    fun composeRedactsPhoneDigitsHiddenInStructuralThreadName() {
        // الخيط بنيوي ASCII فيمر، لكن دفاعه الثانوي (Redactor) ينقّي الهاتف منه.
        val report = CrashReportComposer.compose(
            timestampMillis = 0L,
            threadName = "sync-01012345678",
            throwable = IllegalStateException("boom"),
        )

        assertFalse(report.contains("01012345678"))
        assertTrue(report.contains("thread: sync-[REDACTED_PHONE]"))
    }

    @Test
    fun composeWalksCauseChainUpToMaxDepthWithoutMessages() {
        var throwable: Throwable = IllegalStateException("root-cause-secret")
        repeat(times = 8) { index ->
            throwable = RuntimeException("wrapper-$index", throwable)
        }

        val report = CrashReportComposer.compose(0L, "main", throwable)

        // 8 غلافين فوق الجذر (9 عقد) ⇒ السلسلة تُقتطع عند MAX_CAUSE_DEPTH.
        val causeMarkers = report.split("caused by:").size - 1
        assertEquals(CrashReportComposer.MAX_CAUSE_DEPTH, causeMarkers)
        // ولا رسالة لأي عقدة تنجو — لا الغلافين ولا الجذر.
        assertFalse(report.contains("wrapper-"))
        assertFalse(report.contains("root-cause-secret"))
        // عقد السلسلة تُدرج type/stack فقط (عدّ بالأسطر لا بالسلاسل — تحصين
        // ضد إطار افتراضي يحمل النص ذاته).
        assertEquals(
            CrashReportComposer.MAX_CAUSE_DEPTH + 1,
            report.lines().count { line -> line.startsWith("type: ") },
        )
    }

    @Test
    fun composeRedactsSelfReferencingCauseSafely() {
        val throwable = IllegalStateException("self-loop-secret")
        // Kotlin Throwable.cause يعيد this عند عدم التهيئة — التأكد من عدم الدوران.
        val report = CrashReportComposer.compose(0L, "main", throwable)

        assertEquals(0, report.split("caused by:").size - 1)
        assertFalse(report.contains("self-loop-secret"))
    }

    @Test
    fun composeIncludesValidCategoryHintAndDropsNonStructural() {
        val valid = CategorizedThrowable(category = "NSRangeException")
        val invalid = CategorizedThrowable(category = "أحمد range exception!")

        val validReport = CrashReportComposer.compose(0L, "main", valid)
        val invalidReport = CrashReportComposer.compose(0L, "main", invalid)

        assertTrue(validReport.contains("category: NSRangeException"))
        assertFalse(invalidReport.contains("category:"), "non-structural category must be dropped whole")
        // Throwable بلا تلميح فئة لا يحمل السطر أصلاً.
        val plainReport = CrashReportComposer.compose(0L, "main", IllegalStateException("boom"))
        assertFalse(plainReport.contains("category:"))
    }

    @Test
    fun composeOfIosShapedThrowablePersistsOnlyTypeAndCategory() {
        // محاكاة IosUncaughtException (منصوي): الاسم المستقر يمر، والسبب الحر
        // ورموز المكدس (قد تحمل بيانات مستخدم) لا تنجو في التقرير الكامل.
        val secretReason = "user@example.com called +20 100 123 4567"
        val secretSymbol = "/Users/secretuser/Library/App + 12345"
        val throwable = CategorizedThrowable(
            category = "NSInvalidArgumentException",
            message = "NSException: NSInvalidArgumentException — $secretReason\ncallStackSymbols:\n$secretSymbol",
        )

        val report = CrashReportComposer.compose(42L, "ios-uncaught-nsexception", throwable)

        assertTrue(report.contains("type: "))
        assertTrue(report.contains("category: NSInvalidArgumentException"))
        assertFalse(report.contains(secretReason))
        assertFalse(report.contains(secretSymbol))
        assertFalse(report.contains("user@example.com"))
    }
}

/** Throwable بفئة مستقرة عبر [CrashCategoryHint] — محاكاة شكل IosUncaughtException. */
private open class CategorizedThrowable(
    private val category: String?,
    message: String? = null,
) : Throwable(message = message), CrashCategoryHint {
    override val crashCategory: String? = category
}
