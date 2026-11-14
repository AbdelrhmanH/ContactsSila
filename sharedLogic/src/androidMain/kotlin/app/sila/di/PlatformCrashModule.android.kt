package app.sila.di

import android.content.Context
import app.sila.core.common.crash.CrashSink
import app.sila.core.common.crash.staleReportNames
import java.io.File
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformCrashModule: Module = module {
    // ADR-0019: sink الكتابة المحلية — في اختبارات المضيف بلا Context يُستبدل
    // بمsink غائب-المضيف (fail-closed) بنمط HostAbsentPlatformRingtoneSource المعتمد.
    single<CrashSink> {
        val context = getOrNull<Context>()
        if (context != null) AndroidCrashFileSink(context = context) else HostAbsentCrashSink
    }
}

/**
 * كاتب ملفات الأعطال على Android (ADR-0019): `<filesDir>/crash/crash-<millis>.txt`
 * — تخزين التطبيق الخاص حصراً (بلا صلاحيات ولا شبكة)، مع احتفاظ أقصى
 * [MAX_RETAINED_REPORTS] تقريراً (الأقدم يُحذف — لا نموّ بلا حدود).
 * مسار العطل الطرفي: لا try/catch هنا — [app.sila.core.common.crash.LocalCrashCapture]
 * يلتقط أي رمي fail-closed.
 */
internal class AndroidCrashFileSink(private val context: Context) : CrashSink {

    override fun write(timestampMillis: Long, content: String): Boolean {
        val dir = File(context.filesDir, CRASH_DIRECTORY).apply { mkdirs() }
        if (!dir.isDirectory) return false

        val file = File(dir, "crash-$timestampMillis.txt")
        file.writeText(text = content, charset = Charsets.UTF_8)
        if (!file.exists()) return false

        pruneOldReports(dir = dir)
        return true
    }

    /** حذف التقارير الأقدم من السقف — الخوارزمية النقية المشتركة [staleReportNames]. */
    private fun pruneOldReports(dir: File) {
        val names = dir.listFiles { candidate -> candidate.isFile && candidate.name.startsWith(PREFIX) }
            ?.map(File::getName)
            ?: return
        staleReportNames(names = names, max = MAX_RETAINED_REPORTS).forEach { stale ->
            File(dir, stale).delete()
        }
    }

    private companion object {
        const val CRASH_DIRECTORY = "crash"
        const val PREFIX = "crash-"
        const val MAX_RETAINED_REPORTS = 5
    }
}

/** بديل المضيف-الغائب (اختبارات JVM بلا Context): fail-closed دائماً. */
private data object HostAbsentCrashSink : CrashSink {
    override fun write(timestampMillis: Long, content: String): Boolean = false
}
