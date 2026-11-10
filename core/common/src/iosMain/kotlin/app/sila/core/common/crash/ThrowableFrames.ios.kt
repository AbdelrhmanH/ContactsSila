package app.sila.core.common.crash

/**
 * K/N: `stackTrace` خاصة runtime — الإطارات تُستخرج من `stackTraceToString`
 * (أسطر ذيلها الـ tab حصراً)، مع حاجز [takeWhile] قبل أي «Caused by:» لو أضافها
 * الـ runtime مستقبلاً — حفاظاً على عقد «إطارات العُقدة الواحدة» حصراً.
 */
internal actual fun stackFramesOf(throwable: Throwable): List<String> =
    throwable.stackTraceToString()
        .lines()
        .takeWhile { line -> !line.startsWith("Caused by:") }
        .filter { line -> line.startsWith("\t") }
