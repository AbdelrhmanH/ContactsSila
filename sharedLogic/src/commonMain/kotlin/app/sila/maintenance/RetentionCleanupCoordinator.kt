package app.sila.maintenance

import app.sila.core.common.AppClock
import app.sila.core.database.dao.CleanupReport
import app.sila.core.database.dao.DataRetentionCleanupDao
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
 * مشغِّل إنتاجي لصيانة الاستبقاء (F-02): يربط [DataRetentionCleanupDao.cleanupExpiredData]
 * بمسار تنفيذ موثوق عند إقلاع التطبيق — سابقاً كان التنفيذ موجوداً بلا أي نداء إنتاجي.
 *
 * الالتزامات:
 * - **مرة واحدة لكل عملية (single-flight):** النداءات المتعددة من مهيئي المنصتين
 *   (SilaApplication / IosKoinBootstrap) وتكرارات إعادة الدخول تُنفَّذ مرة واحدة فقط.
 * - **لا حجب للخيط الرئيسي:** يُطلق على نطاق عمر التطبيق، والتنفيذ الفعلي داخل
 *   `withContext(io)` في الـ DAO.
 * - **Idempotent ومُستأنَف:** حدود الحذف مطلقة (لا نسبية) — الانقطاع في منتصف التنظيف
 *   يُستكمل كاملاً عند إقلاع العملية التالية، والتكرار يُرجع تقريراً صفرياً بلا أثر.
 * - **آمن للتزامن:** التنفيذ تحت `Mutex` الخاصة به + `dbMutex` الخاصة بالـ DAO،
 *   وتعديل الجداول المتعدد يجري داخل معاملة واحدة في الـ DAO.
 * - **تسجيل الإخفاق بلا PII:** يُسجَّل نوع الرسالة والاستثناء فقط (تقرير عدّادات مجمّعة
 *   — لا أسماء ولا أرقام ولا سجل مكالمات)؛ الإخفاق لا يسقط التطبيق.
 */
class RetentionCleanupCoordinator(
    private val cleanup: DataRetentionCleanupDao,
    private val clock: AppClock,
) {

    private val runMutex = Mutex()

    @Volatile
    private var started = false

    private val _lastReport = MutableStateFlow<CleanupReport?>(null)

    /** تقرير آخر تنظيف ناجح في هذه العملية (`null` = لم يُنفَّذ بعد / فشل). */
    val lastReport: StateFlow<CleanupReport?> = _lastReport.asStateFlow()

    /**
     * يُطلق التنظيف على النطاق المعطى (نطاق عمر التطبيق). يُستدعى من مهيئي المنصتين
     * مرة واحدة لكل عملية — الإطلاق المزدوج لا يُكرر التنفيذ (single-flight).
     */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { runOnce() }

    /**
     * ينفّذ التنظيف مرة واحدة لكل عملية: النداء الأول يُنفِّذ ويعيد التقرير،
     * وكل النداءات اللاحقة تعيد `null` بلا أي وصول للقاعدة.
     */
    suspend fun runOnce(): CleanupReport? = runMutex.withLock {
        if (started) return null
        started = true
        try {
            cleanup.cleanupExpiredData(clock.nowEpochMillis())
                .also { report -> _lastReport.value = report }
        } catch (cancellation: CancellationException) {
            // الإلغاء ليس إخفاقاً: نترك العلم مرفوعاً (لا إعادة محاولة في نفس العملية)،
            // والانقطاع يُستكمل عند الإقلاع التالي (حدود مطلقة) — نعيد الرمي التزاماً بالإلغاء.
            throw cancellation
        } catch (failure: Throwable) {
            println(
                "[RetentionCleanup] expired-data cleanup failed: " +
                    "${failure::class.simpleName}: ${failure.message ?: "no message"}"
            )
            null
        }
    }
}
