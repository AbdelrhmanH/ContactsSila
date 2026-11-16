package app.sila.arham.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sila.core.common.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * مستقبِل أفعال الإشعار (T8-08 · §M · D1 بند 7 · إصلاح A-N08):
 * - **«ذكّرني غداً»:** إزاحةُ جدولةٍ لا حالةُ كتابة — إعادة خطة من `now` (الفتحة
 *   التالية المستقبلية) وإعادة جدولتها، ثم مسح الإشعار الحالي.
 * - **«اتصل الآن»:** أُسند إطلاقه إلى النظام مباشرةً عبر `PendingIntent.getActivity`
 *   مع وسم `ACTION_CALL_NOW` وبمكوّنٍ صريح لتفادي فشل `MATCH_DEFAULT_ONLY` (`aInfo is null` / `-91`)
 *   وسياسة ترامبولين الإشعارات (`targetSdk = 37`)، بينما يتولى `MainActivity`
 *   (`ArhamReminderDispatch.onLaunchIntentHandled`) مسح إشعار 2001 عند الاستقبال. يظل هذا
 *   المستقبِل يتكفل ببثّات «اتصل الآن» متبقية من تثبيتٍ أقدم (مسار احتياطي).
 * - **التنظيف والتحصين:** يرفض الأفعال الأجنبية قبل `goAsync()`، ويحلّ `AppDispatchers.io`
 *   عند توفر Koin، ويعيد رمي [CancellationException] و[Error] دون ابتلاع، ويمسح الإشعار في `finally`.
 */
class ArhamNotificationActionReceiver internal constructor(
    private val dispatcherOverride: CoroutineDispatcher? = null,
    private val koinProvider: () -> Koin? = ::resolveArhamKoin,
    private val notificationCanceller: (Context) -> Unit =
        ArhamNotificationPoster::defaultCancelReminderNotification,
) : BroadcastReceiver() {

    /** المنشئ العام الافتراضي لمكوّن `<receiver>` في `AndroidManifest.xml`. */
    constructor() : this(dispatcherOverride = null)

    override fun onReceive(context: Context, intent: Intent) {
        dispatchAction(context = context, intent = intent)
    }

    /** يطلق معالجة الفعل المسموح خارج الخيط الرئيسي ويعيد [Job] لانتظارٍ حتمي في الاختبارات. */
    internal fun dispatchAction(context: Context, intent: Intent): Job? {
        val action = intent.action
        if (
            action != ArhamNotificationPoster.ACTION_REMIND_TOMORROW &&
            action != ArhamNotificationPoster.ACTION_CALL_NOW
        ) {
            return null
        }
        val appContext = context.applicationContext ?: context
        val prefetchedKoin: Result<Koin?>? = if (
            dispatcherOverride == null &&
            action == ArhamNotificationPoster.ACTION_REMIND_TOMORROW
        ) {
            runCatching { koinProvider() }.onFailure {
                if (it is Error) throw it
            }
        } else {
            null
        }
        val dispatcher = dispatcherOverride
            ?: prefetchedKoin?.getOrNull()?.getOrNull<AppDispatchers>()?.io
            ?: Dispatchers.IO
        val pendingResult: PendingResult? = goAsync()
        var launched = false
        try {
            val job = CoroutineScope(dispatcher).launch {
                try {
                    runAction(
                        appContext = appContext,
                        action = action,
                        prefetchedKoin = prefetchedKoin,
                    )
                } finally {
                    pendingResult?.finish()
                }
            }
            launched = true
            return job
        } finally {
            if (!launched) {
                pendingResult?.finish()
            }
        }
    }

    /** تنفيذ الفعل (اتصل الآن / ذكّرني غداً) وفق مزودات المنشئ. */
    internal suspend fun runAction(
        appContext: Context,
        action: String,
        prefetchedKoin: Result<Koin?>? = null,
    ) {
        try {
            runCatching {
                when (action) {
                    ArhamNotificationPoster.ACTION_CALL_NOW -> launchApp(appContext)
                    ArhamNotificationPoster.ACTION_REMIND_TOMORROW -> {
                        val koin = if (prefetchedKoin != null) {
                            prefetchedKoin.getOrThrow()
                        } else {
                            koinProvider()
                        }
                        if (koin == null) {
                            Log.w(TAG, "Koin unavailable at notification action — skipped")
                        } else {
                            ArhamReminderDispatch.rescheduleFromNow(
                                preferences = koin.get(),
                                planner = koin.get(),
                                scheduler = koin.get(),
                            )
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException || it is Error) throw it
                Log.w(TAG, "Notification action failed: ${it.javaClass.simpleName}")
            }
        } finally {
            // ما يحميه finally: ضمان إخفاء إشعار 2001 بعد تفاعل المستخدم على كل المسارات
            // (نجاح/فشل/إلغاء) دون السماح لإخفاق المسح بإسقاط المستقبِل أو طمس الاستثناء.
            ArhamNotificationPoster.cancelReminderNotification(
                context = appContext,
                tag = TAG,
                notificationCanceller = notificationCanceller,
            )
        }
    }

    /** فتح التطبيق بمكوّنه الصريح عبر `createLaunchAppIntent` — بلا extras (مسار احتياطي). */
    private fun launchApp(context: Context) {
        context.startActivity(ArhamNotificationPoster.createLaunchAppIntent(context))
    }

    private companion object {
        const val TAG = "ArhamNotificationActionReceiver"
    }
}
