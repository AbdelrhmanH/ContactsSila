package app.sila.arham.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.sila.feature.arham.domain.ArhamNotificationContent
import app.sila.feature.arham.domain.OccasionAlertKind

/**
 * راصد إشعار أرحام على أندرويد (T8-08 · §M): يحوّل المحتوى الدلالي المشترك
 * ([ArhamNotificationContent]) إلى إشعار بنصوصٍ من موارد الترجمة حصراً — لا نصوص
 * ثابتة في الكود — وأفعالٍ ثابتتين («اتصل الآن» يفتح التطبيق، «ذكّرني غداً» يزحزح
 * الجدولة). **بلا PII في أي Intent:** أسماء الجهات تعيش في نص الإشعار حصراً، والإجراءات
 * تعمل بالنوع لا بالبيانات (خطر DeepSeek 3 — تسريب أسماء عبر extras — مُحسوم بنيوياً).
 */
internal object ArhamNotificationPoster {

    /** معرّف إشعار التذكير الثابت — كل إشعار جديد يستبدل السابق (ميزانية ≤ 1 ظاهر). */
    const val REMINDER_NOTIFICATION_ID = 2001

    /** فعل زر «اتصل الآن» — يفتح التطبيق بلا بيانات مرفقة (ويُميَّز لمسح إشعار 2001). */
    const val ACTION_CALL_NOW = "app.sila.arham.action.CALL_NOW"

    /** فعل زر «ذكّرني غداً» — إزاحةُ جدولةٍ لا حالةُ كتابة (D1 بند 7). */
    const val ACTION_REMIND_TOMORROW = "app.sila.arham.action.REMIND_TOMORROW"

    internal const val CALL_NOW_REQUEST_CODE = 2002
    internal const val REMIND_TOMORROW_REQUEST_CODE = 2003
    internal const val CONTENT_TAP_REQUEST_CODE = 2004

    /** يرصد الإشعار؛ فشل الرصد يُسجَّل بنوعٍ حصراً (بلا PII) ولا يسقط العملية (§O.5). */
    fun post(context: Context, content: ArhamNotificationContent) {
        val (title, text) = resolveText(context = context, content = content)
        val notification = build(
            context = context,
            title = title,
            text = text,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "Arham notification post failed: ${it.javaClass.simpleName}")
        }
    }

    /** نصوص الإشعار من موارد الترجمة — الرسالة المجمعة تفرّق «واحد/كثيرون» (§M). */
    private fun resolveText(
        context: Context,
        content: ArhamNotificationContent,
    ): Pair<String, String> = when (content) {
        is ArhamNotificationContent.Digest ->
            context.getString(app.sila.sharedLogic.R.string.arham_digest_title) to
                // T808-R4: صيغ العدد من `<plurals>` (صفر يُعامَل كفرد كما كان —
                // «يشتاق إليك» بلا إضافة)، والجمع العربي الأصيل من قاعدة CLDR
                // (اثنان/٣–١٠/عشرات) بدل مفاتيح one/many يدوية.
                // **التعيين 0←فئة quantity=1 مقصود وحمّال للسلوك**: الإنجليزية بلا
                // فئة `zero` في CLDR — تمرير 0 مباشرةً يختار `other` («plus 0 more»)
                // تراجعاً عن سلوك T8-08؛ والعربية `zero`==`one` نصاً فلا فرج هناك.
                context.resources.getQuantityString(
                    app.sila.sharedLogic.R.plurals.arham_digest_body,
                    content.additionalOverdueCount.takeIf { it > 0 } ?: 1,
                    content.headlineDisplayName,
                    content.additionalOverdueCount,
                )

        is ArhamNotificationContent.Occasion ->
            context.getString(app.sila.sharedLogic.R.string.arham_digest_title) to
                context.getString(occasionRes(content), content.displayName)
    }

    /** مورد نصّ المناسبة: عيد ميلاد/ذكرى × اليوم/الغد (أربعة مفاتيح — §M). */
    private fun occasionRes(content: ArhamNotificationContent.Occasion): Int {
        val today = content.kind == OccasionAlertKind.ON_DAY
        return when {
            content.type == DATE_TYPE_BIRTHDAY && today ->
                app.sila.sharedLogic.R.string.arham_occasion_birthday_today
            content.type == DATE_TYPE_BIRTHDAY ->
                app.sila.sharedLogic.R.string.arham_occasion_birthday_tomorrow
            today -> app.sila.sharedLogic.R.string.arham_occasion_anniversary_today
            else -> app.sila.sharedLogic.R.string.arham_occasion_anniversary_tomorrow
        }
    }

    /**
     * بناء الإشعار: نقرة المحتوى وزر «اتصل الآن» يطلقان النشاط مباشرةً عبر النظام،
     * مع حمل [ACTION_CALL_NOW] على زر «اتصل الآن» لمسح إشعار 2001 داخل دورة حياة
     * `MainActivity`؛ و«ذكّرني غداً» بثّ للمستقبِل — بلا extras في أي Intent (لا PII).
     */
    private fun build(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, ArhamNotificationChannel.CHANNEL_ID)
            .setSmallIcon(app.sila.sharedLogic.R.drawable.ic_arham_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                openActivityIntent(
                    context = context,
                    intent = createLaunchAppIntent(context),
                    requestCode = CONTENT_TAP_REQUEST_CODE,
                ),
            )
            .addAction(
                /* icon = */ app.sila.sharedLogic.R.drawable.ic_action_call,
                /* title = */ context.getString(app.sila.sharedLogic.R.string.arham_action_call_now),
                // A-N08: النظام يُطلق النشاط مباشرةً عند النقر عبر `PendingIntent.getActivity`
                // لتفادي قيود بدء النشاط من خلفية/ترامبولين الإشعارات على Android 12+ (`targetSdk = 37`)
                // وبمكوّن صريح لتجاوز فشل `MATCH_DEFAULT_ONLY` (`aInfo is null` / `-91`).
                /* intent = */ openActivityIntent(
                    context = context,
                    intent = createCallNowActivityIntent(context),
                    requestCode = CALL_NOW_REQUEST_CODE,
                ),
            )
            .addAction(
                /* icon = */ app.sila.sharedLogic.R.drawable.ic_action_snooze,
                /* title = */ context.getString(app.sila.sharedLogic.R.string.arham_action_remind_tomorrow),
                /* intent = */ actionIntent(
                    context = context,
                    action = ACTION_REMIND_TOMORROW,
                    requestCode = REMIND_TOMORROW_REQUEST_CODE,
                ),
            )
            .build()

    /**
     * نية فتح التطبيق بمكوّنٍ صريح (`ComponentName`): تحلّ `MainActivity` عبر
     * `getLaunchIntentForPackage` وتُثبت احتياطياً باسم النشاط القياسي.
     *
     * 1. **الصراحة (`ComponentName`):** `ActivityTaskSupervisor` / `ActivityStarter`
     *    يفرض `PackageManager.MATCH_DEFAULT_ONLY` للنيات الضمنية؛ وبما أن `<intent-filter>`
     *    لـ `MainActivity` يحمل `MAIN/LAUNCHER` بلا `CATEGORY_DEFAULT`، فإن النية الضمنية
     *    تفشل بـ `aInfo is null` (Android 12/MIUI) و`-91` `START_INTENT_NOT_RESOLVED`
     *    (Android 16) بينما ينجح `cmd package resolve-activity` (flags = 0).
     * 2. **إعادة استخدام المهمة:** `SINGLE_TOP or CLEAR_TOP` يمنعان تكرار `MainActivity`
     *    ويمرران النية إلى `onNewIntent` عند وجود التطبيق في الواجهة أو الخلفية.
     *
     * بلا extras (لا PII) ولا `tel:`.
     */
    internal fun createLaunchAppIntent(context: Context): Intent {
        val resolvedComponent = context.packageManager
            ?.getLaunchIntentForPackage(context.packageName)
            ?.component
            ?: ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
            .setComponent(resolvedComponent)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
    }

    /**
     * نية زر «اتصل الآن» (A-N08): مطابقة لـ [createLaunchAppIntent] بمكوّنٍ صريح،
     * وتستبدل `action` بـ [ACTION_CALL_NOW] ليتمكن `MainActivity` (`onCreate`/`onNewIntent`)
     * من مسح إشعار [REMINDER_NOTIFICATION_ID] (2001) بمجرد فتح الشاشة بنجاح — لأن
     * `setAutoCancel(true)` ينطبق على `contentIntent` (A-N07) لا على `addAction`.
     */
    internal fun createCallNowActivityIntent(context: Context): Intent =
        createLaunchAppIntent(context).setAction(ACTION_CALL_NOW)

    /**
     * يستهلك وسم [ACTION_CALL_NOW] عند فتح `MainActivity` فتحاً طازجاً (`onCreate` مع
     * `savedInstanceState == null` أو `onNewIntent`) ما لم يكن الإطلاق من السجل التاريخي
     * ([Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY])، ثم يمسح إشعار [REMINDER_NOTIFICATION_ID]
     * (2001) ويعيد تعيين `intent.action` إلى [Intent.ACTION_MAIN] حتى لا يُعاد المسح عند التدوير.
     */
    internal fun consumeCallNowIntent(
        context: Context,
        intent: Intent?,
        isRestoredState: Boolean = false,
        notificationCanceller: (Context) -> Unit = { ctx ->
            NotificationManagerCompat.from(ctx).cancel(REMINDER_NOTIFICATION_ID)
        },
    ): Boolean {
        if (isRestoredState || intent == null || intent.action != ACTION_CALL_NOW) return false
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return false
        intent.action = Intent.ACTION_MAIN
        runCatching {
            notificationCanceller(context.applicationContext ?: context)
        }.onFailure {
            if (it is Error) throw it
            Log.w(TAG, "Call-now notification cleanup failed: ${it.javaClass.simpleName}")
        }
        return true
    }

    /**
     * إطلاق النشاط مباشرةً بواسطة النظام عبر `PendingIntent.getActivity` لتجنب قيود
     * ترامبولين الإشعارات (`BroadcastReceiver -> startActivity`) على Android 12+ (`targetSdk = 37`).
     */
    private fun openActivityIntent(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** فعل زر: Intent صريح المكوّن للفعل فقط — بلا extras إطلاقاً (لا PII). */
    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ArhamNotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** اسم فئة النشاط الرئيسي داخل الحزمة الفعلية (`app.sila` أو `app.sila.bench`). */
    internal const val MAIN_ACTIVITY_CLASS_NAME = "app.sila.MainActivity"

    /** نوع التاريخ كما في `ArhamOccasion.type` — مرآة قيم النموذج (لا نص واجهة). */
    private const val DATE_TYPE_BIRTHDAY = "BIRTHDAY"

    private const val TAG = "ArhamNotificationPoster"
}
