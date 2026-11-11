package app.sila.core.common.crash

import app.sila.core.common.Redactor

/**
 * مُركِّب تقرير العطل النصي **v2 (ADR-0019 — تنسيق سماحية بنيوي صارم)**.
 *
 * العقد الخصوصي (إغلاق فجوة التدقيق المستقل مقابل معيار #31 — «لا أسماء، لا
 * أرقام، لا أحداث مكالمات، لا نص حر»): **لا يدخل التقرير أي نص حر إطلاقاً** —
 * رسائل الاستثناءات (`message`) وأسماء الخيوط غير البنيوية و`Throwable.toString()`
 * ورسائل الأسباب كلها **محذوفة حذفاً كلياً** لا منقّاة جزئياً، لأن كشف الأسماء
 * العربية/اللاتينية إرشادي غير موثوق (قرار موثق سلفاً). الحقول المسموحة حصراً:
 *
 * - الترويسة الثابتة + `format: 2` + `timestamp_epoch_ms` — مُنتَج المُركِّب
 *   الموثوق (رقمي بحت، لا يمر بـ `Redactor` كي لا يُفسد بإنقاذ نمط الهاتف).
 * - `thread:` — **بنيوي فقط**: `[A-Za-z0-9_\- ]` بحد 64 (main، DefaultDispatcher-worker-1،
 *   ios-uncaught-nsexception) — غير المطابق يُحذف السطر كاملاً.
 * - `type:` — معرف فئة الاستثناء منقّط صارم (معرف كود لا نص مستخدم) — غير
 *   المطابق يعود `<unclassified>`.
 * - `category:` — تلميح فئة مستقر عبر [CrashCategoryHint] (اسم `NSException`
 *   على iOS) — بشرط البنية ذاتها، وإلا حُذف.
 * - `stack:` — إطارات بنيوية حصراً `[A-Za-z0-9_$.,:()/+\-]` بلا مسافات (يُسقط
 *   إطارات K/N العنوانية التي قد تحوي مسارات مضيف بنطاقات مستخدمين — حد
 *   منصوي موثق: iOS يُدرج type/category فقط)، بحد [MAX_FRAMES_PER_NODE]
 *   إطاراً للعُقدة، وبعد التحقق تُمرر دفاعاً ثانوياً عبر `Redactor.scrub`.
 *   **بقية موثقة:** إطار K/N نظري بلا مسافات يحمل مساراً قد يمر بالبوابة —
 *   إطارات K/N الفعلية تفصل بـ ` + ` فتُسقط؛ البوابة الصارمة تُقلّص السطح
 *   ولا تدّعي الإغلاق المطلق.
 * - `caused by:` — سلسلة الأسباب بحد [MAX_CAUSE_DEPTH] مستويات، **بنفس قواعد
 *   الحقول أعلاه وبلا رسائل**.
 *
 * سلسلة الالتقاط نفسها (`LocalCrashCapture`) fail-closed كما هي: أي رمي من
 * المُركِّب أو مصدر الإطارات ⇒ لا تقرير إطلاقاً.
 */
object CrashReportComposer {

    /** أقصى عمق لسلسلة الأسباب (cause chain) في التقرير الواحد. */
    const val MAX_CAUSE_DEPTH: Int = 5

    /** أقصى عدد إطارات مكدس للعُقدة الواحدة (حتمية حجم الملف). */
    const val MAX_FRAMES_PER_NODE: Int = 24

    /** اسم النوع الرجعي حين لا يوفر الكلاس معرفاً بنيوياً صالحاً. */
    internal const val UNCLASSIFIED_TYPE: String = "<unclassified>"

    fun compose(timestampMillis: Long, threadName: String, throwable: Throwable): String = buildString {
        appendLine("SILA LOCAL CRASH REPORT (ADR-0019 — device-local only, never uploaded)")
        appendLine("format: 2")
        // الترويسة مُنتَج المُركِّب حصراً (موثوق — لا تنقية): طابع epoch الرقمي
        // البحت يطابق نمط هاتف Redactor لو نُقّي كله فليصير [REDACTED_PHONE]!
        appendLine("timestamp_epoch_ms: $timestampMillis")
        if (isValidStructuralThreadName(threadName)) {
            appendLine("thread: ${Redactor.scrub(threadName)}")
        }
        appendNode(throwable, depth = 0)
    }

    /** عُقدة سلسلة الأسباب: type/category/stack فقط — **لا رسالة إطلاقاً**. */
    private fun StringBuilder.appendNode(throwable: Throwable, depth: Int) {
        appendLine("type: ${safeTypeName(throwable::class.qualifiedName ?: throwable::class.simpleName)}")
        (throwable as? CrashCategoryHint)?.crashCategory
            ?.takeIf { candidate -> isValidStructuralIdentifier(candidate) }
            ?.let { category -> appendLine("category: ${Redactor.scrub(category)}") }
        appendLine("stack:")
        normalizedFrames(stackFramesOf(throwable)).forEach { frame ->
            appendLine("\tat ${Redactor.scrub(frame)}")
        }
        val cause = throwable.cause
        if (cause != null && cause !== throwable && depth < MAX_CAUSE_DEPTH) {
            appendLine("caused by:")
            appendNode(cause, depth + 1)
        }
    }

    // ============================================================
    // بوابات السماحية البنيوية (دوال نقية داخلية — مُختبَرة مباشرة)
    // ============================================================

    /** اسم خيط بنيوي: ASCII-معرفات ومسافات وحرفتان فقط، بحد 64. */
    internal fun isValidStructuralThreadName(name: String): Boolean = name.matches(THREAD_PATTERN)

    /** معرف منقّط صارم (فئة/فئة استثناء): مقاطع `[A-Za-z_$][A-Za-z0-9_$]*` بحد 8 نقاط. */
    internal fun isValidStructuralIdentifier(name: String): Boolean = name.matches(TYPE_PATTERN)

    /** إطار مكدس بنيوي: معرفات ومسارات ملفات ودقات سطور — **بلا مسافات** (يُسقط إطارات K/N العنوانية). */
    internal fun isValidStructuralFrame(frame: String): Boolean = frame.matches(FRAME_PATTERN)

    /** اسم النوع الآمن: المعرف الصالح كما هو (منقّى دفاعاً ثانوياً)، وإلا `<unclassified>`. */
    internal fun safeTypeName(name: String?): String {
        val candidate = name?.trim().orEmpty()
        if (!isValidStructuralIdentifier(candidate)) return UNCLASSIFIED_TYPE
        return Redactor.scrub(candidate)
    }

    /**
     * خط إطارات العُقدة: تجريد البادئة البنيوية (trim + `at `)، الحذف البنيوي
     * deny-by-default، ثم سقف [MAX_FRAMES_PER_NODE] — حتمي الترتيب والعدد.
     */
    internal fun normalizedFrames(frames: List<String>): List<String> = frames.asSequence()
        .map { frame -> frame.trim().removePrefix("at ").trim() }
        .filter { frame -> frame.isNotEmpty() && isValidStructuralFrame(frame) }
        .take(MAX_FRAMES_PER_NODE)
        .toList()

    private val THREAD_PATTERN = Regex("^[A-Za-z0-9_\\- ]{1,64}$")
    private val TYPE_PATTERN = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,7}$")
    private val FRAME_PATTERN = Regex("^[A-Za-z0-9_$.,:()/+\\-]{1,240}$")
}
