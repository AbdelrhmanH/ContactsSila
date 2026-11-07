package app.sila.arham.emergency

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sila.core.common.AppClock
import app.sila.core.database.dao.ContactDao
import app.sila.core.model.RingingStatus
import app.sila.feature.arham.domain.EmergencyRingingController
import app.sila.feature.arham.domain.EmergencySessionRepository
import app.sila.feature.arham.domain.RecordEmergencyAttemptUseCase
import app.sila.feature.arham.domain.RecordEmergencyAttemptUseCase.AttemptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.core.Koin

/**
 * خدمة رنين الطوارئ الأمامية (T8-10 · AC-14 · §K.6 · Issue #29) — **منفّذ حصراً**:
 * كل القرارات (بوابة الفئة، حد المعدل، السقف، stop-on-block، الحسم idempotent) في
 * نطاق المستخدمات المشتركة؛ هذه الخدمة تطلق المكالمة وترصد الحالة وتستدعي
 * [RecordEmergencyAttemptUseCase] بين الجولات (CALL→IDLE→5ث→CALL حتى الإجابة/التوقف/10).
 *
 * - FGS نوع `phoneCall` (§K.6 حرفياً) — بلا `RECORD_AUDIO` ولا ميكروفون (مِظلّة
 *   الخصوصية: تبديل الصوت مسؤولية الواجهة T8-11 إن أُضيف).
 * - **ids-only في الـ Intent** (عقد بلا PII — T8-08): الهوية تُستعاد لحظياً من Room.
 * - `START_STICKY` + إعادة الالتحاق في `onCreate` (§N.5: المحاسبة تنجو، بلا وعدٍ
 *   بأن الرنين نفسه ينجو)؛ الإيقاف من الإشعار والواجهة نفس الانتقال النهائي.
 * - الإشعار الجارٍ على قناة `arham_emergency` (`IMPORTANCE_LOW`) بزر إيقاف — بلا
 *   PII في الـ extras قط.
 * - كل إخفاق داخلي يُسجَّل بنوعٍ حصراً ولا يسقط العملية (§O.5).
 */
class ArhamEmergencyRingingService : LifecycleService() {

    private val recordAttempt: RecordEmergencyAttemptUseCase by lazy { get() }
    private val contactDao: ContactDao by lazy { get() }

    override fun onCreate() {
        super.onCreate()
        EmergencyNotificationChannel.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, INVALID_ID) ?: INVALID_ID
        val contactId = intent?.getLongExtra(EXTRA_CONTACT_ID, INVALID_ID) ?: INVALID_ID

        if (sessionId == INVALID_ID || contactId == INVALID_ID) {
            // إعادة تشغيل sticky بلا intent: لا رنين من دون ids — المحاسبة (تسوية الجلسة
            // العالقة إلى INTERRUPTED والحد اليومي السليم) مسؤولية تسوية SilaApplication (§N.5).
            Log.w(TAG, "Sticky restart without ids; orphan accounting left to app-start reconcile")
            stopSelf()
            return START_STICKY
        }

        runCatching {
            startInForeground(contactId = contactId)
        }.onFailure {
            Log.w(TAG, "startForeground failed: ${it.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runRingingLoop(sessionId = sessionId, contactId = contactId)
        }
        return START_STICKY
    }

    /** الحلقة الكاملة للجلسة — منفّذ حصراً؛ كل القرارات في النطاق (§J). */
    internal suspend fun runRingingLoop(sessionId: Long, contactId: Long) {
        val watcher = TelephonyStateWatcher(this)
        try {
            while (true) {
                val contact = contactDao.getContactById(contactId) ?: break // إجحاف عند المحاولة
                val phone = contact.phones.firstOrNull { it.isPrimary }?.e164
                    ?: contact.phones.firstOrNull()?.e164
                if (phone.isNullOrEmpty()) break

                updateNotificationDisplayName(contactId = contactId) // اسم الهدف في الإشعار الجاري
                watcher.register()
                placeCall(phone)

                // OFFHOOK بعد الطلب = اتصال مُلاحَظ (§N.4: إشارة أندرويد للإجابة — بلا
                // اختلاق مدة). انقضاء المهلة/خمولٌ أول = بلا إجابة ⇒ جولة التالي.
                val observed = watcher.awaitNextState(
                    predicate = { it == TelephonyManager.CALL_STATE_OFFHOOK },
                    timeoutMillis = ANSWER_DETECTION_TIMEOUT_MILLIS,
                )
                if (observed == TelephonyManager.CALL_STATE_OFFHOOK) {
                    finishAnswered(sessionId)
                    break
                }
                watcher.unregister()

                delay(ATTEMPT_COOLDOWN_MILLIS) // 5 ثوانٍ (§C.1-8)

                when (recordAttempt(sessionId = sessionId, contactId = contactId)) {
                    is AttemptResult.ContinueRinging -> Unit // الجولة التالية
                    is AttemptResult.MaxReached,
                    AttemptResult.Blocked,
                    AttemptResult.ContactDeleted,
                    AttemptResult.SessionFinished -> break // الحسم تم في النطاق
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ringing loop failed: ${e.javaClass.simpleName}")
        } finally {
            watcher.unregister()
            stopSelf()
        }
    }

    /** طلب المكالمة عبر الطلبار — `ACTION_CALL` بلا extras (الرقم في عقد `tel:` حصراً). */
    private fun placeCall(phoneE164: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneE164")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Dial failed: ${it.javaClass.simpleName}") }
    }

    private fun finishAnswered(sessionId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val koin: Koin = get()
                val repository = koin.get<EmergencySessionRepository>()
                val clock = koin.get<AppClock>()
                repository.finishSession(sessionId, RingingStatus.ANSWERED, clock.nowEpochMillis())
                // محو تتبع المنفذ عند الإجابة (مراجعة T8-10 · P3-3): المنفذ لا يبقى
                // «حاملاً» جلسة محسومة — stop() idempotent (آمن قبل/بعد البدء)،
                // وتوقف الخدمة النهائي بيد finally (stopSelf).
                koin.get<EmergencyRingingController>().stop()
            }.onFailure { Log.w(TAG, "Answer finish failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun startInForeground(contactId: Long) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // فشل هادئ — طلب الإذن مسؤولية الواجهة (T8-11)؛ بلا إذن لا حلقة إطلاقاً.
            Log.w(TAG, "CALL_PHONE not granted; emergency ringing refused")
            throw SecurityException("CALL_PHONE not granted")
        }
        // بلا قراءة DB هنا (الخيط الرئيسي) — الهوية تُحدَّد في حلقة IO وتُحدِّث الإشعار.
        val notification = buildStopNotification(displayName = null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** تحديث نص الإشعار باسم الهدف بعد حسمه في حلقة IO — نفس المعرف (بلا PII في extras). */
    internal suspend fun updateNotificationDisplayName(contactId: Long) {
        val displayName = runCatching { contactDao.getContactById(contactId)?.name?.displayName }
            .getOrNull()?.orEmpty()
        val manager = getSystemService(NotificationManagerCompat::class.java)
        runCatching {
            manager?.notify(NOTIFICATION_ID, buildStopNotification(displayName = displayName))
        }.onFailure { Log.w(TAG, "Notification update failed: ${it.javaClass.simpleName}") }
    }

    /** إشعار جارٍ: عنوان + نص بالاسم إن حُسم (سطح مستخدم محلي) + زر إيقاف — ids-only حصراً. */
    private fun buildStopNotification(displayName: String?): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { appIntent ->
            PendingIntent.getActivity(
                this,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getBroadcast(
            this,
            STOP_REQUEST_CODE,
            Intent(this, EmergencyStopActionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, EmergencyNotificationChannel.CHANNEL_ID)
            .setSmallIcon(app.sila.sharedLogic.R.drawable.ic_arham_reminder)
            .setContentTitle(getString(app.sila.sharedLogic.R.string.arham_emergency_notification_title))
            .setContentText(
                getString(
                    app.sila.sharedLogic.R.string.arham_emergency_notification_body,
                    displayName?.takeIf { it.isNotEmpty() }
                        ?: getString(app.sila.sharedLogic.R.string.arham_emergency_notification_target_unknown),
                )
            )
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                getString(app.sila.sharedLogic.R.string.arham_emergency_stop),
                stopIntent,
            )
            .build()
    }

    companion object {
        internal const val EXTRA_SESSION_ID = "arham_emergency_session_id"
        internal const val EXTRA_CONTACT_ID = "arham_emergency_contact_id"
        internal const val NOTIFICATION_ID = 3001
        internal const val STOP_REQUEST_CODE = 3001
        private const val INVALID_ID = -1L
        private const val ANSWER_DETECTION_TIMEOUT_MILLIS = 30_000L
        private const val ATTEMPT_COOLDOWN_MILLIS = 5_000L // §C.1-8: 5 ثوانٍ بين الجولات
        private const val TAG = "ArhamEmergencySrv"

        /** نقطة الدخول المعيارية — ids-only؛ بلا إذن ⇒ لا إطلاق (فشل هادئ). */
        fun start(context: Context, sessionId: Long, contactId: Long) {
            val intent = Intent(context, ArhamEmergencyRingingService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_CONTACT_ID, contactId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ArhamEmergencyRingingService::class.java))
        }
    }
}
