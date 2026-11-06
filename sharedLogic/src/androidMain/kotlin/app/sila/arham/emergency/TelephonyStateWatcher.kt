package app.sila.arham.emergency

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * راصد حالة الهاتف لحلقة الطوارئ (T8-10 · §K.6) — **حالة فقط بلا هوية** (نمط T8-09):
 * يُسجَّل عند بدء المحاولة ويُفكّ بعدها؛ الانتقالات تُنشر على تدفق داخلي، والانتظار
 * مؤقّت (بلا تعليق أبدي). فرعا التوزيع كلاهما: `TelephonyCallback` (31+) و
 * `PhoneStateListener` بتوقيعيه (26–30 حقيقي / شادو 33+) — نفس تعاهر T8-09.
 */
internal class TelephonyStateWatcher(private val context: Context) {

    private val transitions = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    private var registered31: TelephonyCallback? = null
    private var registered26: PhoneStateListener? = null

    fun register() {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(callState: Int) {
                        transitions.tryEmit(callState)
                    }
                }
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                registered31 = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    fun onCallStateChanged(state: Int) {
                        transitions.tryEmit(state)
                    }

                    override fun onCallStateChanged(state: Int, callDisplayName: String?) {
                        transitions.tryEmit(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registered26 = listener
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watcher registration failed: ${e.javaClass.simpleName}")
        }
    }

    fun unregister() {
        try {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            registered31?.let { telephony?.unregisterTelephonyCallback(it) }
            registered26?.let {
                @Suppress("DEPRECATION")
                telephony?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watcher unregistration failed: ${e.javaClass.simpleName}")
        }
        registered31 = null
        registered26 = null
    }

    /**
     * انتظار أول انتقالٍ يطابق [predicate] ضمن [timeoutMillis] — `null` عند انقضاء
     * المهلة (المكالمة لم تُحدِث الحالة المتوقعة: بلا إجابة/بلا اتصال).
     */
    suspend fun awaitNextState(predicate: (Int) -> Boolean, timeoutMillis: Long): Int? =
        withTimeoutOrNull(timeoutMillis) { transitions.first { predicate(it) } }

    private companion object {
        const val TAG = "ArhamEmergencyWatch"
    }
}
