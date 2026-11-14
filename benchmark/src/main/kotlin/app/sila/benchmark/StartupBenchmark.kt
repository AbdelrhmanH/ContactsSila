package app.sila.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * منصة قياس إقلاع التطبيق (T10 · ADR-0018): تُنفَّذ على العتاد المرجعي المسمّى حصراً —
 * لا على CI ولا على محاكٍ (أرقام المحاكي مستبعدة منهجياً في T8 §2 المحمية).
 *
 * الشرط المقيس الآن من بوابة المرحلة 1 (§5): الإقلاع البارد ≤ 1.5 ثانية. الاستخدام
 * `CompilationMode.None()` لقياس حالة بلا Baseline Profile (ProfileInstaller حاضر
 * ولا ملف تعريف في المرحلة 1) — فالأساس المقيس هو بلا تفضيلات AOT.
 *
 * التنفيذ على جهاز متصل:
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 * (لصف واحد: `-P android.testInstrumentationRunnerArguments.class=...`).
 *
 * خارج النطاق هنا عمداً: قياس الإطارات (jank) وفحص المكررات على الجهاز — قيادتهما
 * للواجهة تشترط testTags مرجعية مُتحقق منها على الجهاز، وتُنفَّذ في جلسات T7/T10
 * على العتاد المرجعي (D1 Redmi Note 9S) — الأرقام لا تُقدَّر أبداً ولا تُستدل من مضيف.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupColdCompilationNone() = startup(CompilationMode.None())

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    private companion object {
        /** معرف التطبيق المقيس — بنية القياس المعزولة في `:androidApp`
         *  (`applicationIdSuffix = ".bench"` — تذكرة #95/تعديل ADR-0018): لا يمس
         *  هوية الإنتاج `app.sila` ولا حزمة الأجهزة `app.sila.benchmark`. */
        const val TARGET_PACKAGE = "app.sila.bench"

        /** العدد الموثق للتكرارات في قياس إقلاع مستقر على العتاد المرجعي. */
        const val ITERATIONS = 10
    }
}
