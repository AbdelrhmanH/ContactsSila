package app.sila.arham.observer

import app.sila.core.common.AppClock
import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.CallKit.CXCall
import platform.CallKit.CXCallObserver
import platform.CallKit.CXCallObserverDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * مراقب حالة المكالمات على iOS (T8-12 · خطة سطر 550 · AC-6): جسر `CXCallObserver`
 * **ضمن حياة التطبيق فقط** — لا مراقبة خلفية ولا صلاحيات نظام (واجهة `CXCallObserver`
 * تعمل بلا مفاتيح Info.plist ولا استحقاقات)، وبدئياته ترِد على الطابور الرئيسي ثم يُطلق
 * الربطُ خارجَه على نطاق المستدعى (مرآة `AndroidCallStateObserver` تماماً).
 *
 * **حالة فقط بلا هوية (AC-17):** `CXCall` لا يعرض رقماً قط — «لا رقم» يبقى مجهولاً
 * بأمانة، و`uuid` معرّفُ جلسةٍ عشوائي يُستخدم مفتاحَ تتبعٍ حصراً — **لا يُشتقّ منه
 * هوية قط** (قاعدة الخطة). الاتجاه (`isOutgoing`) منصّيّ حقيقي يُستثمر لاستبعاد
 * نهاياتِ الوارد من الربط بنيوياً (انظر [IosCallStateTracker]).
 *
 * **لا بديلة للتأكيد قط:** النهاية المرصودة تُربَط بمحاولة صادرةٍ مفتوحة عبر
 * [CorrelateObservedCallEndUseCase] (نتيجة `CONNECTED`/ثقة `MEDIUM`) — لا تكتب
 * `lastContactedAt` ولا تُنتج إثبات تواصلٍ مستقلاً (بوابة AC-8 آلةُ الحالات حصراً).
 * التسجيل الفاشل يُسجَّل بنوعٍ حصراً — بلا انهيار (§O.5).
 */
@OptIn(ExperimentalForeignApi::class)
class IosCallStateObserver(
    private val correlate: CorrelateObservedCallEndUseCase,
    private val clock: AppClock,
) : ArhamCallStateObserver {

    private var started = false
    private var callObserver: CXCallObserver? = null
    private var delegate: ObserverDelegate? = null

    /** للاختبارات (friend): هل استُوفيت شروط التسجيل فعلاً. */
    internal val isRegistered: Boolean get() = started

    // مسارات start/stop تعمل من مهيئ عمر التطبيق حصراً (نطاق واحد) — علمٌ بسيط يكفي
    // للـ idempotence (لا `@Synchronized` — JVM-حصراً، ومسار iOS أحادي الاستدعاء).
    override fun start(scope: CoroutineScope) {
        if (started) return
        val observer = CXCallObserver()
        val newDelegate = ObserverDelegate(
            tracker = tracker,
            correlate = correlate,
            clock = clock,
            scope = scope,
        )
        runCatching {
            // جسر K/N يعيد Unit هنا (لا تُبلَّغ نتيجة تسجيل BOOL) — الفشل المنصّي الوحيد
            // الممكن استثناءٌ يُلتقط؛ الوضع الطبيعي: التسجيل صامت وناجح.
            observer.setDelegate(delegate = newDelegate, queue = dispatch_get_main_queue())
        }.onFailure {
            IosCallStateObserver.logTypeOnly("CXCallObserver registration failed", it)
            return
        }
        callObserver = observer
        delegate = newDelegate
        started = true
    }

    override fun stop() {
        if (!started) return
        runCatching {
            callObserver?.setDelegate(delegate = null, queue = null)
        }.onFailure { IosCallStateObserver.logTypeOnly("Observer unregistration failed", it) }
        callObserver = null
        delegate = null
        tracker.clear()
        started = false
    }

    /**
     * المتتبع النقي المشترك بين دورات المراقبة — حالة المكالمات المفتوحة تُصفّى عند
     * `stop` فقط ([IosCallStateTracker.clear]).
     */
    private val tracker = IosCallStateTracker()

    /**
     * جسر مندوب `CXCallObserver` — خيط الطابور الممرَّر؛ التحويل بدئياتٍ صارمة فقط:
     * `(uuid, isOutgoing, hasConnected, hasEnded, now)` — لا أنواع `CXCall` تعبر.
     */
    private class ObserverDelegate(
        private val tracker: IosCallStateTracker,
        private val correlate: CorrelateObservedCallEndUseCase,
        private val clock: AppClock,
        private val scope: CoroutineScope,
    ) : NSObject(), CXCallObserverDelegateProtocol {

        override fun callObserver(callObserver: CXCallObserver, callChanged: CXCall) {
            val end = tracker.onCallReported(
                callId = callChanged.UUID.UUIDString,
                outgoing = callChanged.outgoing,
                hasConnected = callChanged.hasConnected,
                hasEnded = callChanged.hasEnded,
                nowMillis = clock.nowEpochMillis(),
            ) ?: return
            // عودةُ النهاية تُربَط خارج الخيط الرئيسي بفشلٍ هادئ (§O.5 — مرآة أندرويد).
            scope.launch {
                runCatching { correlate(end) }
                    .onFailure { logTypeOnly("Observed-end correlation failed", it) }
            }
        }
    }

    internal companion object {
        const val TAG = "ArhamCallObserverIOS"

        fun logTypeOnly(message: String, error: Throwable) {
            println("$TAG: $message: ${error::class.simpleName}")
        }

        fun logTypeOnly(message: String, error: NSError) {
            println("$TAG: $message: ${error.domain}/${error.code}")
        }
    }
}
