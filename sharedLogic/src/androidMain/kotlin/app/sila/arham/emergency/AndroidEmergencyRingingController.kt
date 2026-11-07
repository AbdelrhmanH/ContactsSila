package app.sila.arham.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.sila.feature.arham.domain.EmergencyRingingController

/**
 * منفذ حلقة الطوارئ الحقيقي (T8-10 · Issue #29) — جسر رقيق إلى [ArhamEmergencyRingingService]:
 * ids-only حصراً، وبوابة إذن `CALL_PHONE` فشلٌ هادئ (طلبُ الإذن مسؤولية الواجهة
 * T8-11 — البداية بلا إذن تُسجَّل بنوعٍ ولا تُطلق الحلقة إطلاقاً). Idempotence:
 * الخدمة نفسها لا تُطلق مرتين لنفس الجلسة (تتبع داخلي).
 */
class AndroidEmergencyRingingController(
    private val context: Context,
) : EmergencyRingingController {

    private var runningSessionId: Long? = null

    @Synchronized
    override fun start(sessionId: Long, contactId: Long) {
        if (runningSessionId != null) {
            Log.w(TAG, "Emergency ringing already running; duplicate start ignored (idempotent)")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CALL_PHONE not granted ? emergency ringing refused; UI owns the request")
            return
        }
        runCatching {
            ArhamEmergencyRingingService.start(context, sessionId = sessionId, contactId = contactId)
            runningSessionId = sessionId
        }.onFailure { Log.w(TAG, "FGS start failed: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    override fun stop() {
        if (runningSessionId == null) return // آمن قبل البدء — idempotent
        runCatching { ArhamEmergencyRingingService.stop(context) }
            .onFailure { Log.w(TAG, "FGS stop failed: ${it.javaClass.simpleName}") }
        runningSessionId = null
    }

    @Synchronized
    override fun isRunning(sessionId: Long): Boolean = runningSessionId == sessionId

    private companion object {
        const val TAG = "ArhamEmergencyCtl"
    }
}
