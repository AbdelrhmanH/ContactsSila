package app.sila.maintenance

import app.sila.feature.arham.domain.MaterializeTopFiveUseCase
import app.sila.feature.arham.domain.TopFiveMaterializationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * مشغِّل إنتاجي لتجسيد «أهم 5 أشخاص» عند إقلاع التطبيق (T8-15 · F-1 من دفتر الملاحظات):
 * يربط [MaterializeTopFiveUseCase] بمسار تنفيذ موثوق عند البدء — سابقاً كانت الحالة
 * الاستخدامية مُختبرة بلا أي نداء إنتاجي (نفس شكل F-02 قبل [RetentionCleanupCoordinator]).
 *
 * الالتزامات (مرآة حرفية لمشغِّل الاستبقاء):
 * - **مرة واحدة لكل عملية (single-flight):** نداءات مهيئي المنصتين
 *   (SilaApplication / IosKoinBootstrap) وتكرارات إعادة الدخول تُنفَّذ مرة واحدة فقط.
 * - **لا حجب للخيط الرئيسي:** يُطلق على نطاق عمر التطبيق حصراً.
 * - **Idempotent عبر الإقلاعات:** حارس العلامة الواحدة داخل الحالة الاستخدامية أولاً
 *   (§F.11-هـ)؛ وفشلُ المحاولة داخل العملية يبقي الخاد غير مرفوع (§F.10 «العلامة لا
 *   تُرفع على فشل»)، فتُستكمل عند إقلاع العملية التالية — إعادة التشغيل idempotent.
 * - **لا يُرفع الخاد أبداً على فشل:** الاستثناءات تُبتلع هنا بعد تسجيلها بنوعٍ حصراً
 *   (بلا PII — لا أسماء ولا أرقام)، ولا يسقط الإخفاق التطبيق؛ النتيجة `null` على
 *   [lastOutcome] تميّز الفشل عن كل نتيجة حقيقية.
 */
class ArhamMaterializationCoordinator(
    private val materialize: MaterializeTopFiveUseCase,
) {

    private val runMutex = Mutex()

    @Volatile
    private var started = false

    private val _lastOutcome = MutableStateFlow<TopFiveMaterializationOutcome?>(null)

    /** نتيجة آخر تشغيل ناجح في هذه العملية (`null` = لم يُنفَّذ بعد / فشل مُبتلَع). */
    val lastOutcome: StateFlow<TopFiveMaterializationOutcome?> = _lastOutcome.asStateFlow()

    /**
     * يُطلق التجسيد على النطاق المعطى (نطاق عمر التطبيق). يُستدعى من مهيئي المنصتين
     * مرة واحدة لكل عملية — الإطلاق المزدوج لا يُكرر التنفيذ (single-flight).
     */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { runOnce() }

    /**
     * ينفّذ التجسيد مرة واحدة لكل عملية: النداء الأول يُنفِّض ويعيد النتيجة،
     * وكل النداءات اللاحقة تعيد `null` بلا وصول للمستودع.
     */
    suspend fun runOnce(): TopFiveMaterializationOutcome? = runMutex.withLock {
        if (started) return null
        started = true
        try {
            materialize().also { outcome -> _lastOutcome.value = outcome }
        } catch (cancellation: CancellationException) {
            // الإلغاء ليس إخفاقاً: نترك العلم مرفوعاً (لا إعادة محاولة في نفس العملية)،
            // والانقطاع يُستكمل عند الإقلاع التالي (الخاد غير مرفوع) — نعيد الرمي التزاماً بالإلغاء.
            throw cancellation
        } catch (failure: Throwable) {
            println(
                "[ArhamMaterialization] top-five materialization failed: " +
                    "${failure::class.simpleName}: ${failure.message ?: "no message"}"
            )
            null
        }
    }
}
