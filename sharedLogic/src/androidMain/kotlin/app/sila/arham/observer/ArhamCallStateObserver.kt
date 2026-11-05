package app.sila.arham.observer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.sila.core.common.AppClock
import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import app.sila.feature.arham.domain.ObservedCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * عقد مراقب حالة الهاتف (T8-09 · AC-4/AC-17) — **حالة فقط بلا هوية**: لا رقم ولا
 * جهة ولا محتوى يمرّ هنا قط؛ النهاية المرصودة تُربَط بمحاولة صِلَة المفتوحة عبر
 * [CorrelateObservedCallEndUseCase] (حل D-2 بالربط لا باختراع المفاتيح).
 */
interface ArhamCallStateObserver {

    /**
     * يبدأ المراقبة — **idempotent**: الاستدعاءات المتكررة بلا أثر. البوابة صلاحية
     * `READ_PHONE_STATE` حصراً (بلا مفتاح DataStore جديد — قرار مثبت): مرفوضة ⇒
     * لا تسجيل ولا عملية، بسجلٍّ بنوعٍ حصراً.
     */
    fun start(scope: CoroutineScope)

    /** يوقف المراقبة ويفكّ كل التسجيلات (idempotent — آمن قبل `start`). */
    fun stop()
}

/** تنفيذ مضيف الاختبارات/JVM الصامت — نمط `HostAbsentPlatformSource` (ADR-0010 §5). */
object HostAbsentCallStateObserver : ArhamCallStateObserver {
    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit
}

/**
 * مراقب الحالة الحقيقي فوق `TelephonyManager`: `TelephonyCallback` (API 31+) أو
 * `PhoneStateListener` (API 26–30). عمليات الرجوع تصل على الخيط الرئيسي؛ الإطلاق
 * إلى الربط خارجها على نطاق المستدعى. التسجيل الفاشل يُسجَّل بنوعٍ حصراً — بلا انهيار.
 */
class AndroidCallStateObserver(
    private val context: Context,
    private val correlate: CorrelateObservedCallEndUseCase,
    private val clock: AppClock,
) : ArhamCallStateObserver {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var registered31: TelephonyCallback? = null
    private var registered26: PhoneStateListener? = null
    private var observedState = ObservedCallState()

    /** للاختبارات (friend): هل استُوفيت شروط التسجيل فعلاً. */
    internal val isRegistered: Boolean get() = started

    @Synchronized
    override fun start(scope: CoroutineScope) {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted ? observer idle; manual confirmation path unaffected")
            return
        }
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: run {
            Log.w(TAG, "TelephonyManager unavailable ? observer idle")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(callState: Int) {
                        onObservedState(callState, scope)
                    }
                }
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                registered31 = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    // توقيعا التوزيع معاً: (I)V هو ما توزّعه أجهزة 26–32 فعلياً
                    // (حُذف من stubs الـ 37 فتعاهُرٌ بلا `override` يحفظ توقيع JVM)،
                    // و(IL/java/lang/String;)V توزّعها منصات 33+ وشادو Robolectric.
                    fun onCallStateChanged(state: Int) {
                        onObservedState(state, scope)
                    }

                    override fun onCallStateChanged(state: Int, callDisplayName: String?) {
                        onObservedState(state, scope)
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registered26 = listener
            }
        } catch (e: Exception) {
            Log.w(TAG, "Observer registration failed: ${e.javaClass.simpleName}")
            registered31 = null
            registered26 = null
            return
        }
        started = true
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        val telephony = context.getSystemService(TelephonyManager::class.java)
        try {
            registered31?.let { callback ->
                telephony?.unregisterTelephonyCallback(callback)
            }
            registered26?.let { listener ->
                @Suppress("DEPRECATION")
                telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Observer unregistration failed: ${e.javaClass.simpleName}")
        }
        registered31 = null
        registered26 = null
        started = false
    }

    /**
     * انتقال الحالة إلى بدائيات الحامل — **بلا أي هوية** (الرنين/الرفع/الخمول لحظات
     * حصراً). عودةُ الخمول المنتجة تُربَط خارج الخيط الرئيسي بفشلٍ هادئ (§O.5).
     */
    private fun onObservedState(callState: Int, scope: CoroutineScope) {
        val now = clock.nowEpochMillis()
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING -> observedState.onRinging(now)
            TelephonyManager.CALL_STATE_OFFHOOK -> observedState.onOffhook(now)
            TelephonyManager.CALL_STATE_IDLE -> {
                val end = observedState.onIdle(now) ?: return
                scope.launch {
                    runCatching { correlate(end) }
                        .onFailure { Log.w(TAG, "Observed-end correlation failed: ${it.javaClass.simpleName}") }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ArhamCallObserver"
    }
}
