package app.sila.arham.notification

import android.app.PendingIntent
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

    /** فعل زر «اتصل الآن» — يفتح التطبيق بلا بيانات مرفقة. */
    const val ACTION_CALL_NOW = "app.sila.arham.action.CALL_NOW"

    /** فعل زر «ذكّرني غداً» — إزاحةُ جدولةٍ لا حالةُ كتابة (D1 بند 7). */
    const val ACTION_REMIND_TOMORROW = "app.sila.arham.action.REMIND_TOMORROW"

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

    /** بناء الإشعار: نقرة المحتوى تفتح التطبيق، والفعلان يرسلان للمستقبِل بلا extras. */
    private fun build(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, ArhamNotificationChannel.CHANNEL_ID)
            .setSmallIcon(app.sila.sharedLogic.R.drawable.ic_arham_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                /* icon = */ app.sila.sharedLogic.R.drawable.ic_action_call,
                /* title = */ context.getString(app.sila.sharedLogic.R.string.arham_action_call_now),
                /* intent = */ actionIntent(context = context, action = ACTION_CALL_NOW, requestCode = 2002),
            )
            .addAction(
                /* icon = */ app.sila.sharedLogic.R.drawable.ic_action_snooze,
                /* title = */ context.getString(app.sila.sharedLogic.R.string.arham_action_remind_tomorrow),
                /* intent = */ actionIntent(context = context, action = ACTION_REMIND_TOMORROW, requestCode = 2003),
            )
            .build()

    /** نقرة المحتوى: فتح التطبيق عبر MAIN/LAUNCHER على الحزمة صراحةً — بلا extras. */
    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        2004,
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    /** نوع التاريخ كما في `ArhamOccasion.type` — مرآة قيم النموذج (لا نص واجهة). */
    private const val DATE_TYPE_BIRTHDAY = "BIRTHDAY"

    private const val TAG = "ArhamNotificationPoster"
}
