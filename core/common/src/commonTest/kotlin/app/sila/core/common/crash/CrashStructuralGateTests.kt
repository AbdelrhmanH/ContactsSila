package app.sila.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات بوابات السماحية البنيوية للمُركِّب (ADR-0019 — تنسيق v2): دوال
 * نقية داخلية هي **عقد الخصوصية ذاته** — اسم خيط/معرف/إطار غير بنيوي يُحذف
 * كلياً (deny-by-default)، والحدود المسموحة موثقة لكل بواب.
 */
class CrashStructuralGateTests {

    @Test
    fun structuralThreadNameGate() {
        assertTrue(CrashReportComposer.isValidStructuralThreadName("main"))
        assertTrue(CrashReportComposer.isValidStructuralThreadName("DefaultDispatcher-worker-1"))
        assertTrue(CrashReportComposer.isValidStructuralThreadName("ios-uncaught-nsexception"))
        assertFalse(CrashReportComposer.isValidStructuralThreadName("thread-أحمد"))
        assertFalse(CrashReportComposer.isValidStructuralThreadName("bad/name"))
        assertFalse(CrashReportComposer.isValidStructuralThreadName("x".repeat(65)))
    }

    @Test
    fun structuralIdentifierGate() {
        assertTrue(CrashReportComposer.isValidStructuralIdentifier("java.lang.NullPointerException"))
        assertTrue(CrashReportComposer.isValidStructuralIdentifier("app.sila.MainActivity\$onCreate\$1"))
        assertTrue(CrashReportComposer.isValidStructuralIdentifier("NSRangeException"))
        assertFalse(CrashReportComposer.isValidStructuralIdentifier("bad type!"))
        assertFalse(CrashReportComposer.isValidStructuralIdentifier("أحمد"))
        assertFalse(CrashReportComposer.isValidStructuralIdentifier(""))
    }

    @Test
    fun structuralFrameGate() {
        // إطار JVM البنيوي القياسي يمر.
        assertTrue(CrashReportComposer.isValidStructuralFrame("app.sila.feature.main.MainViewModel.onToggle(MainViewModel.kt:188)"))
        assertTrue(CrashReportComposer.isValidStructuralFrame("app.sila.MainActivity\$onCreate\$1.onClick(MainActivity.kt:45)"))
        // إطار K/N العنواني (مسافة + مسار مضيف قد يحمل اسماً شخصياً) يُسقط كلياً — حد منصوي موثق.
        assertFalse(CrashReportComposer.isValidStructuralFrame("0x0000000102c3d000 /Users/secretuser/libApp + 12345"))
        assertFalse(CrashReportComposer.isValidStructuralFrame("Native Method"))
        assertFalse(CrashReportComposer.isValidStructuralFrame(""))
    }

    @Test
    fun safeTypeNameFallsBackToUnclassified() {
        assertEquals(
            "java.lang.IllegalStateException",
            CrashReportComposer.safeTypeName("java.lang.IllegalStateException"),
        )
        assertEquals(CrashReportComposer.UNCLASSIFIED_TYPE, CrashReportComposer.safeTypeName("bad type!"))
        assertEquals(CrashReportComposer.UNCLASSIFIED_TYPE, CrashReportComposer.safeTypeName(null))
        assertEquals(CrashReportComposer.UNCLASSIFIED_TYPE, CrashReportComposer.safeTypeName("   "))
    }

    @Test
    fun normalizedFramesStripsPrefixesDropsNonStructuralAndCapsCount() {
        val frames = listOf(
            "at app.sila.Foo.bar(Foo.kt:10)",
            "\tat app.sila.Baz.qux(Baz.kt:3)",
            "at 0x0000000102c3d000 /Users/secretuser/libApp + 12345",
            "أحمد()",
            "   ",
        )

        assertEquals(
            listOf("app.sila.Foo.bar(Foo.kt:10)", "app.sila.Baz.qux(Baz.kt:3)"),
            CrashReportComposer.normalizedFrames(frames),
        )

        val overflow = (1..(CrashReportComposer.MAX_FRAMES_PER_NODE + 10))
            .map { index -> "app.sila.Foo.method$index(Foo.kt:$index)" }
        assertEquals(
            CrashReportComposer.MAX_FRAMES_PER_NODE,
            CrashReportComposer.normalizedFrames(overflow).size,
        )
    }
}
