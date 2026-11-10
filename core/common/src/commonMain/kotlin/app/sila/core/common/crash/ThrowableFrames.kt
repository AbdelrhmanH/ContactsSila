package app.sila.core.common.crash

/**
 * إطارات المكدس للعُقدة الواحدة حصراً — **بلا سلسلة الأسباب المدمجة** (ADR-0019):
 * expect/actual لأن JVM يملك خاصية `stackTrace` العامة بينما K/N يجعلها خاصة
 * (`NativePtrArray` داخلي) فلا سبيل للإطارات إلا عبر `stackTraceToString` مع
 * حاجز يقف قبل أي «Caused by:» محتملة مستقبلاً. حد العمق في [CrashReportComposer]
 * يسري على المنصتين بفضل هذا الفصل — دمج `stackTraceToString` على JVM يطبع
 * السلسلة كاملة فيهزم الحد ويكرر العقد.
 */
internal expect fun stackFramesOf(throwable: Throwable): List<String>
