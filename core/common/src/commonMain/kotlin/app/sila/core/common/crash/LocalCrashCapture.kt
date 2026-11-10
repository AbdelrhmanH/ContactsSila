package app.sila.core.common.crash

/**
 * منظّم التقاط الأعطال المحلي (ADR-0019 — قرار المالك 2026-11-10): يجعل مفتاح
 * `crashReportsEnabled` حاكماً فعلياً و`Redactor` نافذاً لأغراضه الموثقة —
 * **محلي حصراً: ملف في تخزين التطبيق الخاص، بلا شبكة ولا SDK سحابي في المرحلة 1**.
 *
 * العقد fail-closed على كامل المسار (احترام اختيار المستخدم أولاً):
 * - فشل قراءة البوابة أو مهلتها أو إعادتها false ⇒ لا كتابة.
 * - فشل التركيب أو الكتابة (رمي أو false) ⇒ يُبتلع ويعود false — لا استثناءات
 *   تتصاعد من مسار العطل الطرفي إطلاقاً.
 *
 * التركيب المنصوي (تثبيت المعالج + [CrashSink] الفعلي) مسؤولية المنصتين:
 * `SilaApplication` على Android و`IosKoinBootstrap` على iOS — عبر mوديول Koin
 * المشترك في `sharedLogic` (`crashCaptureModule` + `platformCrashModule`).
 */
class LocalCrashCapture(
    private val isCaptureEnabled: () -> Boolean,
    private val sink: CrashSink,
) {

    /**
     * يلتقط العطل إن كانت البوابة مفتوحة، ويعود دائماً دون رمي.
     * @return true عند كتابة الملف فعلاً، false عند أي بوابة/إخفاق.
     */
    fun capture(timestampMillis: Long, threadName: String, throwable: Throwable): Boolean {
        val enabled = runCatching { isCaptureEnabled() }.getOrDefault(false)
        if (!enabled) return false

        val content = runCatching {
            CrashReportComposer.compose(timestampMillis = timestampMillis, threadName = threadName, throwable = throwable)
        }.getOrNull() ?: return false

        return runCatching { sink.write(timestampMillis = timestampMillis, content = content) }.getOrDefault(false)
    }
}
