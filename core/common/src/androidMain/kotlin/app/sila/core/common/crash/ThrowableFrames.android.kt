package app.sila.core.common.crash

/** JVM/Android: الخاصية عامة — إطارات العُقدة مباشرة بلا أي أسباب مدمجة. */
internal actual fun stackFramesOf(throwable: Throwable): List<String> =
    throwable.stackTrace.map { frame -> frame.toString() }
