package app.sila.core.common.crash

import app.sila.core.common.Redactor

/**
 * مُركِّب تقرير العطل النصي (ADR-0019): يجمّع الطابع الزمني واسم الخيط وسلسلة
 * الأسباب (بحد أقصى [MAX_CAUSE_DEPTH] مستويات — مكدسات عميقة مرضية تُقتطع لا
 * تُغرِق الملف) مع **تنقية جزئية بحدود ثقة**: الترويسة (الطابع والاسم العام
 * للنمط) مُنتَج المُركِّب موثوق لا تُنقّى، ومحتوى العطل (اسم النوع/الرسالة/
 * الإطارات) غير موثوق فتُنقّى أجزاؤه عبر `Redactor.scrub` — أرقام الهواتف
 * والبريد لا تصل الملف بأي شكل (SECURITY_PRIVACY.md §3.7). تنقية التقرير
 * كاملاً دفعة واحدة خاطئة: طابع epoch الرقمي البحت يطابق نمط هاتف Redactor
 * فيُفسد الترويسة.
 *
 * قرار موثق: لا نمط تنقية لأسماء عربية — نسبة الإيجاب الزائف على رسائل تشخيصية
 * عربية مرتفعة (أي رسالتان عربيتان متتاليتان تُعتبران اسماً)، والضوابط المعوضة
 * هي التخزين المحلي الخاص + البوابة fail-closed + التنقية الصارمة للهواتف/البريد.
 */
object CrashReportComposer {

    /** أقصى عمق لسلسلة الأسباب (cause chain) في التقرير الواحد. */
    const val MAX_CAUSE_DEPTH: Int = 5

    fun compose(timestampMillis: Long, threadName: String, throwable: Throwable): String = buildString {
        appendLine("SILA LOCAL CRASH REPORT (ADR-0019 — device-local only, never uploaded)")
        // الترويسة مُنتَج المُركِّب حصراً (موثوق — لا تنقية): طابع epoch الرقمي
        // البحت يطابق نمط هاتف Redactor لو نُقّي كله فليصير [REDACTED_PHONE]!
        appendLine("timestamp_epoch_ms: $timestampMillis")
        appendLine("thread: ${Redactor.scrub(threadName)}")
        appendChain(throwable, depth = 0)
    }

    private fun StringBuilder.appendChain(throwable: Throwable, depth: Int) {
        val typeName = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable"
        appendLine("type: ${Redactor.scrub(typeName)}")
        throwable.message?.let { appendLine("message: ${Redactor.scrub(it)}") }
        appendLine("stack:")
        // الخاصية المشتركة (لا stackTraceToString): الأخير يدمج «Caused by:» كاملة
        // على JVM فيهزم حد العمق ويكرر العقد — الإطارات فقط هنا، والسلسلة يدوي حصراً.
        // كل جزء من محتوى العطل غير موثوق ⇒ يُنقّى جزأياً (حدود الثقة).
        stackFramesOf(throwable).forEach { frame -> appendLine("\tat ${Redactor.scrub(frame)}") }
        val cause = throwable.cause
        if (cause != null && cause !== throwable && depth < MAX_CAUSE_DEPTH) {
            appendLine("caused by:")
            appendChain(cause, depth + 1)
        }
    }
}
