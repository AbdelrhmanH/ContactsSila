package app.sila.arham.notification

import app.sila.feature.arham.domain.ArhamNotificationContent
import app.sila.feature.arham.domain.OccasionAlertKind
import platform.Foundation.NSBundle

/**
 * مُحلّ نصوص إشعارات أرحام على iOS (T8-12 · §M): يحوّل المحتوى الدلالي المشترك
 * ([ArhamNotificationContent]) إلى نصٍّ من موارد الترجمة حصراً — `Localizable.strings`
 * في حزمة التطبيق (لا نصوص ثابتة في الكود — قاعدة §6a نفسها).
 *
 * **اصطلاح مفاتيح iOS المعمول به** (مرآة مفاتيح `details_arham_days_*` من T6):
 * اختيار يدوي لمفتاح الجمع (`_one`/`_many`) بدل `.stringsdict` — لا توجد `.stringsdict`
 * في هذا الهدف إطلاقاً؛ تحديد «فرد/جمع» هنا قرارُ المُنبِّئ النقي [formatDigestBody]
 * المُختبر، والنصّ نفسه من الحزمة.
 */
interface IosArhamNotificationStrings {

    /** عنوان إشعار التذكير اليومي (مشترك بين الرسالة المجمعة والمناسبة — كأندرويد). */
    fun digestTitle(): String

    /** جسد الرسالة المجمعة: متأخرٌ واحد بلا إضافة، أو أكثر بـ«+N». */
    fun digestBody(headlineDisplayName: String, additionalOverdueCount: Int): String

    /** جسد تنبيه المناسبة: عيد ميلاد/ذكرى × اليوم/الغد (أربعة مفاتيح — §M). */
    fun occasionBody(kind: OccasionAlertKind, type: String, displayName: String): String

    /** عنوان زر «اتصل الآن» (فعل مقدمة — يفتح التطبيق). */
    fun callNowActionTitle(): String

    /** عنوان زر «ذكّرني غداً» (فعل خلفي — إزاحة جدولة). */
    fun remindTomorrowActionTitle(): String
}

/**
 * التنفيذ فوق حزمة التطبيق: `NSBundle.mainBundle.localizedString(forKey:value:table:)`
 * — القيمة الاحتياطية هي المفتاح نفسه (سلوك iOS القياسي عند غياب الترجمة — يُكشف بالاختبار
 * على المحاكي بدل انهيار أو نصّ إنجليزي مُختلق في الإصدار العربي).
 */
class IosBundleArhamNotificationStrings(private val bundle: NSBundle = NSBundle.mainBundle) :
    IosArhamNotificationStrings {

    private fun localized(key: String): String =
        bundle.localizedStringForKey(key = key, value = key, table = null)

    override fun digestTitle(): String = localized(KEY_DIGEST_TITLE)

    override fun digestBody(headlineDisplayName: String, additionalOverdueCount: Int): String =
        formatDigestBody(
            additionalOverdueCount = additionalOverdueCount,
            oneFormat = localized(KEY_DIGEST_BODY_ONE),
            manyFormat = localized(KEY_DIGEST_BODY_MANY),
            displayName = headlineDisplayName,
        )

    override fun occasionBody(kind: OccasionAlertKind, type: String, displayName: String): String =
        formatOccasionBody(
            kind = kind,
            type = type,
            displayName = displayName,
            birthdayToday = localized(KEY_OCCASION_BIRTHDAY_TODAY),
            birthdayTomorrow = localized(KEY_OCCASION_BIRTHDAY_TOMORROW),
            anniversaryToday = localized(KEY_OCCASION_ANNIVERSARY_TODAY),
            anniversaryTomorrow = localized(KEY_OCCASION_ANNIVERSARY_TOMORROW),
        )

    override fun callNowActionTitle(): String = localized(KEY_ACTION_CALL_NOW)

    override fun remindTomorrowActionTitle(): String = localized(KEY_ACTION_REMIND_TOMORROW)

    private companion object {
        const val KEY_DIGEST_TITLE = "arham_digest_title"
        const val KEY_DIGEST_BODY_ONE = "arham_digest_body_one"
        const val KEY_DIGEST_BODY_MANY = "arham_digest_body_many"
        const val KEY_OCCASION_BIRTHDAY_TODAY = "arham_occasion_birthday_today"
        const val KEY_OCCASION_BIRTHDAY_TOMORROW = "arham_occasion_birthday_tomorrow"
        const val KEY_OCCASION_ANNIVERSARY_TODAY = "arham_occasion_anniversary_today"
        const val KEY_OCCASION_ANNIVERSARY_TOMORROW = "arham_occasion_anniversary_tomorrow"
        const val KEY_ACTION_CALL_NOW = "arham_action_call_now"
        const val KEY_ACTION_REMIND_TOMORROW = "arham_action_remind_tomorrow"
    }
}

/**
 * جسد الرسالة المجمعة — اختيار «فرد/جمع» نقي (اصطلاح مفاتيح iOS اليدوي): الصفر والواحد
 * بلا إضافة (مرآة تعيين Android T808-R4: `takeIf { it > 0 } ?: 1` على فئة الكمية —
 * «دعوة متأخرة» مفردة حتى لو كانت الإضافة واحدة)، وأثنان فأكثر بـ«+N» (تصغيرُ فئات
 * CLDR العربية إلى مفاتيح iOS اليدوية — بلا `.stringsdict`). التنسيق عبر `%@` للاسم
 * و`%lld` للعدد (اصطلاح `.strings` — متوافق مع ملفات الموارد).
 */
internal fun formatDigestBody(
    additionalOverdueCount: Int,
    oneFormat: String,
    manyFormat: String,
    displayName: String,
): String {
    val format = if (additionalOverdueCount > 1) manyFormat else oneFormat
    return if (additionalOverdueCount > 1) {
        format.replace("%@", displayName)
            .replace("%lld", additionalOverdueCount.toString())
    } else {
        format.replace("%@", displayName)
    }
}

/**
 * جسد تنبيه المناسبة — اختيار المفتاح من أربعة نقي (نوع التاريخ `BIRTHDAY` مرآةُ قيم
 * النموذج كما في `ArhamNotificationPoster` أندرويد؛ `OccasionAlertKind.ON_DAY` = اليوم).
 */
internal fun formatOccasionBody(
    kind: OccasionAlertKind,
    type: String,
    displayName: String,
    birthdayToday: String,
    birthdayTomorrow: String,
    anniversaryToday: String,
    anniversaryTomorrow: String,
): String {
    val today = kind == OccasionAlertKind.ON_DAY
    val format = when {
        type == DATE_TYPE_BIRTHDAY && today -> birthdayToday
        type == DATE_TYPE_BIRTHDAY -> birthdayTomorrow
        today -> anniversaryToday
        else -> anniversaryTomorrow
    }
    return format.replace("%@", displayName)
}

/** نوع التاريخ كما في `ArhamOccasion.type` — مرآة قيم النموذج (لا نص واجهة). */
private const val DATE_TYPE_BIRTHDAY = "BIRTHDAY"
