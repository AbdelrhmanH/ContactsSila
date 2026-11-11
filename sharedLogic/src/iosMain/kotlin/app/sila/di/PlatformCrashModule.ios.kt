package app.sila.di

import app.sila.arham.widget.IosArhamWidgetSnapshotPublisher
import app.sila.core.common.crash.CrashCategoryHint
import app.sila.core.common.crash.CrashSink
import app.sila.core.common.crash.LocalCrashCapture
import app.sila.core.common.crash.staleReportNames
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDate
import platform.Foundation.NSException
import platform.Foundation.NSFileManager
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.timeIntervalSince1970

internal actual val platformCrashModule: Module = module {
    // ADR-0019: sink الكتابة المحلية — المسار يُحسم وقت الكتابة (لا فشل عند الإنشاء).
    single<CrashSink> { IosCrashFileSink() }
}

/**
 * كاتب ملفات الأعطال على iOS (ADR-0019): `Application Support/Sila/crash/crash-<millis>.txt`
 * — نفس جذر التخزين الدائم المعتمد في [IosKoinBootstrap.resolveApplicationSupportPath]
 * (fail-fast عند تعذر المسار ⇒ يُلتقط fail-closed عند المنظم)، مع احتفاظ أقصى
 * [MAX_RETAINED_REPORTS] تقريراً (الأقدم يُحذف).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCrashFileSink : CrashSink {

    override fun write(timestampMillis: Long, content: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        val crashDir = "${IosKoinBootstrap.resolveApplicationSupportPath()}/$CRASH_DIRECTORY"

        if (!fileManager.fileExistsAtPath(path = crashDir)) {
            fileManager.createDirectoryAtPath(
                path = crashDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }

        val path = "$crashDir/crash-$timestampMillis.txt"
        // K/N 2.4 يخفي أعضاء NSString.writeToFile عن الاستيراد — مسار POSIX المضمون
        // (نمط المستودع الموثق: IosArhamWidgetSnapshotPublisher.writeAtomically —
        // كتابة ذرّية عبر .tmp ثم rename، والترميز UTF-8 مضمون عبر ByteArray).
        val written = IosArhamWidgetSnapshotPublisher.writeAtomically(
            path = path,
            content = content.encodeToByteArray(),
        )
        if (written) pruneOldReports(fileManager = fileManager, dir = crashDir)
        return written
    }

    /** حذف التقارير الأقدم من السقف — الخوارزمية النقية المشتركة [staleReportNames]. */
    private fun pruneOldReports(fileManager: NSFileManager, dir: String) {
        val names = (fileManager.contentsOfDirectoryAtPath(path = dir, error = null) ?: emptyList<Any>())
            .map { "$it" }
            // مرشّح البادئة نفسه كنظير Android: ملفات غريبة في المجلد لا تُحسب
            // ضمن سقف الاحتفاظ ولا تُحذف (تصحيح مراجعة 2026-11-11).
            .filter { candidate -> candidate.startsWith(PREFIX) }
        staleReportNames(names = names, max = MAX_RETAINED_REPORTS).forEach { stale ->
            fileManager.removeItemAtPath(path = "$dir/$stale", error = null)
        }
    }

    private companion object {
        const val CRASH_DIRECTORY = "crash"
        const val PREFIX = "crash-"
        const val MAX_RETAINED_REPORTS = 5
    }
}

/**
 * NSException كـ Throwable ليمرّ عبر منظم [LocalCrashCapture] المشترك — اسم
 * الاستثناء المستقر يُمرر عبر [CrashCategoryHint] ليظهر في التقرير كفئة آمنة
 * محققة البنية (تنسيق v2)، بينما الرسالة (السبب الحر ورموز المكدس) **لا تدخل
 * التقرير إطلاقاً** — النص الحر محذوف حذفاً كلياً بقرار التنسيق الصارم.
 */
internal class IosUncaughtException(
    exceptionName: String,
    reason: String?,
    callStackSymbols: List<String>,
) : Throwable(
    message = buildString {
        append("NSException: $exceptionName")
        reason?.let { append(" — $it") }
        if (callStackSymbols.isNotEmpty()) {
            appendLine()
            appendLine("callStackSymbols:")
            callStackSymbols.forEach { appendLine(it) }
        }
    },
),
    CrashCategoryHint {

    override val crashCategory: String = exceptionName
}

/**
 * جسر الالتقاط بين مؤشر الدالة C لـ `NSSetUncaughtExceptionHandler` ومنطق
 * [LocalCrashCapture]: `staticCFunction` **لا يلتقط حالة محلية** — لذا تُحمَّل
 * لامدا الالتقاط هنا عند الإقلاع عبر [install]، ويُنفَّذ الالتقاط على خيط
 * الانهيار ذاته عبر مؤشر الدالة المعلّم top-level (لا التقاط ضمني لـ `this`).
 *
 * **الحد الصادق الموثق (ADR-0019):** هذا المعالج يلتقط `NSException` حصراً —
 * الانهيارات الخام لـ Kotlin/Native (استثناء Kotlin غير الملتقط يُجهض العملية
 * عبر مسار runtime مستقل) والإشارات القاتلة (SIGSEGV وغيرها) **خارج الالتقاط**.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCrashBridge {

    /** لامدا الالتقاط المحمّلة عند الإقلاع — null قبل التركيب ⇒ dispatch صامت. */
    @Volatile
    internal var onUncaught: ((Throwable) -> Unit)? = null

    internal fun install(capture: LocalCrashCapture) {
        onUncaught = { throwable ->
            val nowMillis = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
            capture.capture(
                timestampMillis = nowMillis,
                threadName = THREAD_NAME,
                throwable = throwable,
            )
        }
        NSSetUncaughtExceptionHandler(iosUncaughtExceptionHandler)
    }

    internal fun dispatch(exception: NSException?) {
        val current = exception ?: return
        val throwable = IosUncaughtException(
            exceptionName = current.name ?: "NSException",
            reason = current.reason,
            callStackSymbols = current.callStackSymbols.map { "$it" },
        )
        onUncaught?.invoke(throwable)
    }

    private const val THREAD_NAME = "ios-uncaught-nsexception"
}

/** مؤشر الدالة C المعلّم top-level — الوحيد المقبول لـ staticCFunction (بلا التقاط). */
@OptIn(ExperimentalForeignApi::class)
private val iosUncaughtExceptionHandler = staticCFunction { exception: NSException? ->
    IosCrashBridge.dispatch(exception)
}
