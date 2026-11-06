package app.sila.arham.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sila.arham.notification.resolveArhamKoin
import app.sila.feature.arham.domain.StopEmergencyRingingUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * متلقي زر الإيقاف في الإشعار الجاري (T8-10 · §N.8 · Issue #29) — رقيقٌ كنمط T8-08:
 * فعلٌ واحد idempotent عبر [StopEmergencyRingingUseCase] (نفس الانتقال النهائي
 * `STOPPED_BY_USER` كسطح الواجهة — توقفٌ مزدوج عملية ساكنة). بلا PII في الـ Intent
 * (بلا extras قط)، وكل إخفاق يُسجَّل بنوعٍ حصراً بلا انهيار (§O.5) — الغائب في
 * `runCatching` لا يُمسّ سطح التأكيد اليدوي.
 */
class EmergencyStopActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync() // null عند الاستدعاء المباشر (اختبار) — لا انهيار (§O.5)
        val finish = { pendingResult?.finish() }
        val koin = resolveArhamKoin()
        if (koin == null) {
            Log.w(TAG, "Koin unavailable at emergency stop ? skip; retry safe (idempotent)")
            finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stopped = koin.get<StopEmergencyRingingUseCase>()(sessionId = null)
                Log.i(TAG, "Emergency stop broadcast handled (finished=$stopped)")
            } catch (e: Exception) {
                Log.w(TAG, "Emergency stop failed: ${e.javaClass.simpleName}")
            } finally {
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "ArhamEmergencyStop"
    }
}
