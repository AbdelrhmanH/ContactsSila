package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.CallDirection
import app.sila.core.model.Category
import app.sila.core.model.Relation
import app.sila.feature.contacts.presentation.ContactDetailsStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.action_cancel
import contactssila.sharedui.generated.resources.action_confirm
import contactssila.sharedui.generated.resources.action_undo
import contactssila.sharedui.generated.resources.category_emergency
import contactssila.sharedui.generated.resources.category_family
import contactssila.sharedui.generated.resources.category_friends
import contactssila.sharedui.generated.resources.category_services
import contactssila.sharedui.generated.resources.category_uncategorized
import contactssila.sharedui.generated.resources.category_work
import contactssila.sharedui.generated.resources.contacts_loading
import contactssila.sharedui.generated.resources.contacts_emergency_ring_button
import contactssila.sharedui.generated.resources.contacts_emergency_warning_body
import contactssila.sharedui.generated.resources.contacts_emergency_warning_title
import contactssila.sharedui.generated.resources.details_account_device
import contactssila.sharedui.generated.resources.details_account_section
import contactssila.sharedui.generated.resources.details_action_change_category
import contactssila.sharedui.generated.resources.details_action_delete
import contactssila.sharedui.generated.resources.details_action_share_vcard
import contactssila.sharedui.generated.resources.details_addresses_section
import contactssila.sharedui.generated.resources.details_app_signal
import contactssila.sharedui.generated.resources.details_app_telegram
import contactssila.sharedui.generated.resources.details_app_whatsapp
import contactssila.sharedui.generated.resources.details_apps_section
import contactssila.sharedui.generated.resources.details_arham_days_few
import contactssila.sharedui.generated.resources.details_arham_days_many
import contactssila.sharedui.generated.resources.details_arham_days_one
import contactssila.sharedui.generated.resources.details_arham_days_today
import contactssila.sharedui.generated.resources.details_arham_days_two
import contactssila.sharedui.generated.resources.details_arham_section
import contactssila.sharedui.generated.resources.details_back_description
import contactssila.sharedui.generated.resources.details_company_section
import contactssila.sharedui.generated.resources.details_copy_number
import contactssila.sharedui.generated.resources.details_custom_fields_section
import contactssila.sharedui.generated.resources.details_date_anniversary
import contactssila.sharedui.generated.resources.details_date_birthday
import contactssila.sharedui.generated.resources.details_date_custom
import contactssila.sharedui.generated.resources.details_dates_section
import contactssila.sharedui.generated.resources.details_delete_error
import contactssila.sharedui.generated.resources.details_delete_undo_hint
import contactssila.sharedui.generated.resources.details_delete_undo_message
import contactssila.sharedui.generated.resources.details_direction_in
import contactssila.sharedui.generated.resources.details_direction_missed
import contactssila.sharedui.generated.resources.details_direction_out
import contactssila.sharedui.generated.resources.details_edit_description
import contactssila.sharedui.generated.resources.details_email_personal
import contactssila.sharedui.generated.resources.details_emails_section
import contactssila.sharedui.generated.resources.details_favorite_description
import contactssila.sharedui.generated.resources.details_freq_biweekly
import contactssila.sharedui.generated.resources.details_freq_daily
import contactssila.sharedui.generated.resources.details_freq_monthly
import contactssila.sharedui.generated.resources.details_freq_occasions
import contactssila.sharedui.generated.resources.details_freq_weekly
import contactssila.sharedui.generated.resources.details_history_section
import contactssila.sharedui.generated.resources.details_history_view_all
import contactssila.sharedui.generated.resources.details_more_description
import contactssila.sharedui.generated.resources.details_month_1
import contactssila.sharedui.generated.resources.details_month_10
import contactssila.sharedui.generated.resources.details_month_11
import contactssila.sharedui.generated.resources.details_month_12
import contactssila.sharedui.generated.resources.details_month_2
import contactssila.sharedui.generated.resources.details_month_3
import contactssila.sharedui.generated.resources.details_month_4
import contactssila.sharedui.generated.resources.details_month_5
import contactssila.sharedui.generated.resources.details_month_6
import contactssila.sharedui.generated.resources.details_month_7
import contactssila.sharedui.generated.resources.details_month_8
import contactssila.sharedui.generated.resources.details_month_9
import contactssila.sharedui.generated.resources.details_no_phone
import contactssila.sharedui.generated.resources.details_not_found
import contactssila.sharedui.generated.resources.details_notes_section
import contactssila.sharedui.generated.resources.details_phones_section
import contactssila.sharedui.generated.resources.details_primary_phone
import contactssila.sharedui.generated.resources.details_quick_call
import contactssila.sharedui.generated.resources.details_quick_email
import contactssila.sharedui.generated.resources.details_quick_message
import contactssila.sharedui.generated.resources.details_quick_video
import contactssila.sharedui.generated.resources.details_time_hours_few
import contactssila.sharedui.generated.resources.details_time_hours_many
import contactssila.sharedui.generated.resources.details_time_hours_one
import contactssila.sharedui.generated.resources.details_time_hours_two
import contactssila.sharedui.generated.resources.details_time_minutes_few
import contactssila.sharedui.generated.resources.details_time_minutes_many
import contactssila.sharedui.generated.resources.details_time_minutes_one
import contactssila.sharedui.generated.resources.details_time_minutes_two
import contactssila.sharedui.generated.resources.details_time_now
import contactssila.sharedui.generated.resources.details_time_yesterday
import contactssila.sharedui.generated.resources.details_type_home
import contactssila.sharedui.generated.resources.details_type_mobile
import contactssila.sharedui.generated.resources.details_type_other
import contactssila.sharedui.generated.resources.details_type_work
import contactssila.sharedui.generated.resources.details_unfavorite_description
import contactssila.sharedui.generated.resources.details_websites_section
import contactssila.sharedui.generated.resources.relation_aunt_maternal
import contactssila.sharedui.generated.resources.relation_aunt_paternal
import contactssila.sharedui.generated.resources.relation_brother
import contactssila.sharedui.generated.resources.relation_close_friend
import contactssila.sharedui.generated.resources.relation_cousin_maternal
import contactssila.sharedui.generated.resources.relation_cousin_paternal
import contactssila.sharedui.generated.resources.relation_custom
import contactssila.sharedui.generated.resources.relation_daughter
import contactssila.sharedui.generated.resources.relation_father
import contactssila.sharedui.generated.resources.relation_grandfather
import contactssila.sharedui.generated.resources.relation_grandmother
import contactssila.sharedui.generated.resources.relation_in_law
import contactssila.sharedui.generated.resources.relation_mother
import contactssila.sharedui.generated.resources.relation_sister
import contactssila.sharedui.generated.resources.relation_son
import contactssila.sharedui.generated.resources.relation_spouse
import contactssila.sharedui.generated.resources.relation_uncle_maternal
import contactssila.sharedui.generated.resources.relation_uncle_paternal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشة تفاصيل جهة الاتصال (T6 · Issue #27) من موارد الترجمة الحقيقية:
 * `values/` (العربية — الافتراضية) و `values-en/`. لا نص ثابت في كود الواجهة إطلاقاً
 * (AGENTS.md). القوالب ذات العدد (`%d`/`%s`) تُعوَّض يدوياً في `commonMain` —
 * لا `String.format` في الكود المشترك.
 *
 * كل النصوص تُحلّ داخل التركيب (Compose) وتُغلَّف في الدوال اللامركبة للعقد —
 * الدوال لا تستدعي `stringResource` داخلها (غير مسموح خارج التركيب).
 *
 * التنسيق الزمني النسبي ([ContactDetailsStrings.formatInteractionTime]) يدوي في
 * `commonMain` (فروق الملي ثانية) مع `kotlinx-datetime` حصراً لاستخراج أجزاء
 * **التاريخ المحلي** للتواريخ الأقدم من يومين — بدل خوارزمية مدنية يدوية تنحرف
 * قرب منتصف الليل بفارق المنطقة الزمنية.
 */
@Composable
fun rememberContactDetailsStrings(): ContactDetailsStrings {
    val arhamDaysToday = stringResource(Res.string.details_arham_days_today)
    val arhamDaysOne = stringResource(Res.string.details_arham_days_one)
    val arhamDaysTwo = stringResource(Res.string.details_arham_days_two)
    val arhamDaysFew = stringResource(Res.string.details_arham_days_few)
    val arhamDaysMany = stringResource(Res.string.details_arham_days_many)

    val timeNow = stringResource(Res.string.details_time_now)
    val minutesOne = stringResource(Res.string.details_time_minutes_one)
    val minutesTwo = stringResource(Res.string.details_time_minutes_two)
    val minutesFew = stringResource(Res.string.details_time_minutes_few)
    val minutesMany = stringResource(Res.string.details_time_minutes_many)
    val hoursOne = stringResource(Res.string.details_time_hours_one)
    val hoursTwo = stringResource(Res.string.details_time_hours_two)
    val hoursFew = stringResource(Res.string.details_time_hours_few)
    val hoursMany = stringResource(Res.string.details_time_hours_many)
    val timeYesterday = stringResource(Res.string.details_time_yesterday)

    val months = listOf(
        stringResource(Res.string.details_month_1),
        stringResource(Res.string.details_month_2),
        stringResource(Res.string.details_month_3),
        stringResource(Res.string.details_month_4),
        stringResource(Res.string.details_month_5),
        stringResource(Res.string.details_month_6),
        stringResource(Res.string.details_month_7),
        stringResource(Res.string.details_month_8),
        stringResource(Res.string.details_month_9),
        stringResource(Res.string.details_month_10),
        stringResource(Res.string.details_month_11),
        stringResource(Res.string.details_month_12)
    )

    val typeMobile = stringResource(Res.string.details_type_mobile)
    val typeHome = stringResource(Res.string.details_type_home)
    val typeWork = stringResource(Res.string.details_type_work)
    val typeOther = stringResource(Res.string.details_type_other)
    val emailPersonal = stringResource(Res.string.details_email_personal)
    val dateBirthday = stringResource(Res.string.details_date_birthday)
    val dateAnniversary = stringResource(Res.string.details_date_anniversary)
    val dateCustom = stringResource(Res.string.details_date_custom)

    val appWhatsapp = stringResource(Res.string.details_app_whatsapp)
    val appTelegram = stringResource(Res.string.details_app_telegram)
    val appSignal = stringResource(Res.string.details_app_signal)

    val directionIn = stringResource(Res.string.details_direction_in)
    val directionOut = stringResource(Res.string.details_direction_out)
    val directionMissed = stringResource(Res.string.details_direction_missed)

    val freqDaily = stringResource(Res.string.details_freq_daily)
    val freqWeekly = stringResource(Res.string.details_freq_weekly)
    val freqBiweekly = stringResource(Res.string.details_freq_biweekly)
    val freqMonthly = stringResource(Res.string.details_freq_monthly)
    val freqOccasions = stringResource(Res.string.details_freq_occasions)

    val family = stringResource(Res.string.category_family)
    val friends = stringResource(Res.string.category_friends)
    val work = stringResource(Res.string.category_work)
    val services = stringResource(Res.string.category_services)
    val emergency = stringResource(Res.string.category_emergency)
    val uncategorized = stringResource(Res.string.category_uncategorized)

    return ResourceContactDetailsStrings(
        backDescription = stringResource(Res.string.details_back_description),
        editDescription = stringResource(Res.string.details_edit_description),
        favoriteDescription = stringResource(Res.string.details_favorite_description),
        unfavoriteDescription = stringResource(Res.string.details_unfavorite_description),
        moreOptionsDescription = stringResource(Res.string.details_more_description),
        quickActionCall = stringResource(Res.string.details_quick_call),
        quickActionMessage = stringResource(Res.string.details_quick_message),
        quickActionVideo = stringResource(Res.string.details_quick_video),
        quickActionEmail = stringResource(Res.string.details_quick_email),
        primaryPhone = stringResource(Res.string.details_primary_phone),
        copyNumber = stringResource(Res.string.details_copy_number),
        phonesSection = stringResource(Res.string.details_phones_section),
        appsSection = stringResource(Res.string.details_apps_section),
        arhamSection = stringResource(Res.string.details_arham_section),
        historySection = stringResource(Res.string.details_history_section),
        historyViewAll = stringResource(Res.string.details_history_view_all),
        emailsSection = stringResource(Res.string.details_emails_section),
        addressesSection = stringResource(Res.string.details_addresses_section),
        datesSection = stringResource(Res.string.details_dates_section),
        companySection = stringResource(Res.string.details_company_section),
        notesSection = stringResource(Res.string.details_notes_section),
        customFieldsSection = stringResource(Res.string.details_custom_fields_section),
        websitesSection = stringResource(Res.string.details_websites_section),
        accountSection = stringResource(Res.string.details_account_section),
        accountDevice = stringResource(Res.string.details_account_device),
        phoneTypeName = { type ->
            when (type) {
                "MOBILE" -> typeMobile
                "HOME" -> typeHome
                "WORK" -> typeWork
                "OTHER" -> typeOther
                "PERSONAL" -> emailPersonal
                else -> type // الأنواع المخصصة: قيمتها نفسها تسمية المستخدم.
            }
        },
        emailTypeName = { type ->
            when (type) {
                "PERSONAL" -> emailPersonal
                "HOME" -> typeHome
                "WORK" -> typeWork
                "OTHER" -> typeOther
                else -> type
            }
        },
        addressTypeName = { type ->
            when (type) {
                "HOME" -> typeHome
                "WORK" -> typeWork
                "OTHER" -> typeOther
                else -> type
            }
        },
        dateTypeName = { type ->
            when (type) {
                "BIRTHDAY" -> dateBirthday
                "ANNIVERSARY" -> dateAnniversary
                "CUSTOM" -> dateCustom
                else -> type
            }
        },
        connectedAppName = { id ->
            when (id) {
                "whatsapp" -> appWhatsapp
                "telegram" -> appTelegram
                "signal" -> appSignal
                else -> id
            }
        },
        arhamFrequencyLabel = { frequency ->
            when (frequency) {
                ArhamFrequency.DAILY -> freqDaily
                ArhamFrequency.WEEKLY -> freqWeekly
                ArhamFrequency.BIWEEKLY -> freqBiweekly
                ArhamFrequency.MONTHLY -> freqMonthly
                ArhamFrequency.OCCASIONS -> freqOccasions
            }
        },
        arhamDaysSinceLastContact = { days ->
            when (days) {
                0L -> arhamDaysToday
                1L -> arhamDaysOne
                2L -> arhamDaysTwo
                in 3L..10L -> arhamDaysFew.replace("%d", days.toString())
                else -> arhamDaysMany.replace("%d", days.toString())
            }
        },
        formatInteractionTime = { timestampMillis ->
            formatRelativeInteractionTime(
                timestampMillis = timestampMillis,
                nowMillis = Clock.System.now().toEpochMilliseconds(),
                timeNow = timeNow,
                timeYesterday = timeYesterday,
                minutes = RelativeScale(minutesOne, minutesTwo, minutesFew, minutesMany),
                hours = RelativeScale(hoursOne, hoursTwo, hoursFew, hoursMany),
                months = months
            )
        },
        formatDurationSeconds = ::formatCallDuration,
        interactionMissed = directionMissed,
        directionLabel = { direction ->
            when (direction) {
                CallDirection.IN -> directionIn
                CallDirection.OUT -> directionOut
                CallDirection.MISSED -> directionMissed
            }
        },
        deleteUndoMessage = stringResource(Res.string.details_delete_undo_message),
        undo = stringResource(Res.string.action_undo),
        actionShare = stringResource(Res.string.details_action_share_vcard),
        actionChangeCategory = stringResource(Res.string.details_action_change_category),
        actionDelete = stringResource(Res.string.details_action_delete),
        deleteUndoHint = stringResource(Res.string.details_delete_undo_hint),
        categoryTitle = { category ->
            when (category) {
                Category.FAMILY -> family
                Category.FRIENDS -> friends
                Category.WORK -> work
                Category.SERVICES -> services
                Category.EMERGENCY -> emergency
                Category.UNCATEGORIZED -> uncategorized
            }
        },
        relationName = relationNamesMap(
            grandfather = stringResource(Res.string.relation_grandfather),
            grandmother = stringResource(Res.string.relation_grandmother),
            father = stringResource(Res.string.relation_father),
            mother = stringResource(Res.string.relation_mother),
            brother = stringResource(Res.string.relation_brother),
            sister = stringResource(Res.string.relation_sister),
            son = stringResource(Res.string.relation_son),
            daughter = stringResource(Res.string.relation_daughter),
            spouse = stringResource(Res.string.relation_spouse),
            unclePaternal = stringResource(Res.string.relation_uncle_paternal),
            auntPaternal = stringResource(Res.string.relation_aunt_paternal),
            cousinPaternal = stringResource(Res.string.relation_cousin_paternal),
            uncleMaternal = stringResource(Res.string.relation_uncle_maternal),
            auntMaternal = stringResource(Res.string.relation_aunt_maternal),
            cousinMaternal = stringResource(Res.string.relation_cousin_maternal),
            inLaw = stringResource(Res.string.relation_in_law),
            closeFriend = stringResource(Res.string.relation_close_friend),
            custom = stringResource(Res.string.relation_custom)
        ),
        loading = stringResource(Res.string.contacts_loading),
        notFoundMessage = stringResource(Res.string.details_not_found),
        deleteError = stringResource(Res.string.details_delete_error),
        noPhoneNumber = stringResource(Res.string.details_no_phone),
        cancel = stringResource(Res.string.action_cancel),
        emergencyRingButton = stringResource(Res.string.contacts_emergency_ring_button),
        emergencyWarningTitle = stringResource(Res.string.contacts_emergency_warning_title),
        emergencyWarningBody = stringResource(Res.string.contacts_emergency_warning_body),
        emergencyWarningConfirm = stringResource(Res.string.action_confirm)
    )
}

/**
 * تنفيذ نصوص الموارد — كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا.
 */
private data class ResourceContactDetailsStrings(
    override val backDescription: String,
    override val editDescription: String,
    override val favoriteDescription: String,
    override val unfavoriteDescription: String,
    override val moreOptionsDescription: String,
    override val quickActionCall: String,
    override val quickActionMessage: String,
    override val quickActionVideo: String,
    override val quickActionEmail: String,
    override val primaryPhone: String,
    override val copyNumber: String,
    override val phonesSection: String,
    override val appsSection: String,
    override val arhamSection: String,
    override val historySection: String,
    override val historyViewAll: String,
    override val emailsSection: String,
    override val addressesSection: String,
    override val datesSection: String,
    override val companySection: String,
    override val notesSection: String,
    override val customFieldsSection: String,
    override val websitesSection: String,
    override val accountSection: String,
    override val accountDevice: String,
    override val phoneTypeName: (type: String) -> String,
    override val emailTypeName: (type: String) -> String,
    override val addressTypeName: (type: String) -> String,
    override val dateTypeName: (type: String) -> String,
    override val connectedAppName: (id: String) -> String,
    override val arhamFrequencyLabel: (frequency: ArhamFrequency) -> String,
    override val arhamDaysSinceLastContact: (days: Long) -> String,
    override val formatInteractionTime: (timestampMillis: Long) -> String,
    override val formatDurationSeconds: (seconds: Int) -> String,
    override val interactionMissed: String,
    override val directionLabel: (direction: CallDirection) -> String,
    override val deleteUndoMessage: String,
    override val undo: String,
    override val actionShare: String,
    override val actionChangeCategory: String,
    override val actionDelete: String,
    override val deleteUndoHint: String,
    override val categoryTitle: (category: Category) -> String,
    override val relationName: (relation: Relation) -> String,
    override val loading: String,
    override val notFoundMessage: String,
    override val deleteError: String,
    override val noPhoneNumber: String,
    override val cancel: String,
    override val emergencyRingButton: String,
    override val emergencyWarningTitle: String,
    override val emergencyWarningBody: String,
    override val emergencyWarningConfirm: String
) : ContactDetailsStrings

/** أزمنة نسبيّة لسلّم زمني واحد (دقائق أو ساعات) — صيغ العدد العربية. */
private data class RelativeScale(
    val one: String,
    val two: String,
    val fewTemplate: String,
    val manyTemplate: String
)

/**
 * تنسيق زمن التفاعل نسبياً (الآن/دقائق/ساعات/أمس) وحدّياً، وبالتاريخ المحلي
 * لما بعده — تُستخرج أجزاء اليوم من [Instant] عبر منطقة النظام الافتراضية.
 */
private fun formatRelativeInteractionTime(
    timestampMillis: Long,
    nowMillis: Long,
    timeNow: String,
    timeYesterday: String,
    minutes: RelativeScale,
    hours: RelativeScale,
    months: List<String>
): String {
    val minuteMillis = 60_000L
    val hourMillis = 3_600_000L
    val dayMillis = 86_400_000L
    val diff = (nowMillis - timestampMillis).coerceAtLeast(0L)

    return when {
        diff < minuteMillis -> timeNow
        diff < hourMillis ->
            arabicStyleCount(diff / minuteMillis, minutes)

        diff < dayMillis ->
            arabicStyleCount(diff / hourMillis, hours)

        diff < 2 * dayMillis -> timeYesterday
        else -> {
            val timeZone = TimeZone.currentSystemDefault()
            val local = Instant.fromEpochMilliseconds(timestampMillis).toLocalDateTime(timeZone)
            val currentYear = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).year
            val monthName = months[local.monthNumber - 1]
            if (local.year == currentYear) {
                "${local.dayOfMonth} $monthName"
            } else {
                "${local.dayOfMonth} $monthName ${local.year}"
            }
        }
    }
}

/** صيغ العدد: واحد/اثنان/٣–١٠ (جمع)/أكثر من عشرة (فرد عدّي) — عربية أصيلة. */
private fun arabicStyleCount(count: Long, scale: RelativeScale): String = when (count) {
    1L -> scale.one
    2L -> scale.two
    in 3L..10L -> scale.fewTemplate.replace("%d", count.toString())
    else -> scale.manyTemplate.replace("%d", count.toString())
}

/** مدة المكالمة رقميّة عابرة للغات: `M:SS` (مثل 12:05) — لا تحتاج ترجمة. */
private fun formatCallDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutesPart = safe / 60
    val secondsPart = (safe % 60).toString().padStart(2, '0')
    return "$minutesPart:$secondsPart"
}

/** خريطة أسماء القرابات — مفاتيح `relation_*` المشتركة مع التهيئة الأولى (T5). */
private fun relationNamesMap(
    grandfather: String,
    grandmother: String,
    father: String,
    mother: String,
    brother: String,
    sister: String,
    son: String,
    daughter: String,
    spouse: String,
    unclePaternal: String,
    auntPaternal: String,
    cousinPaternal: String,
    uncleMaternal: String,
    auntMaternal: String,
    cousinMaternal: String,
    inLaw: String,
    closeFriend: String,
    custom: String
): (Relation) -> String = { relation ->
    when (relation) {
        Relation.GRANDFATHER -> grandfather
        Relation.GRANDMOTHER -> grandmother
        Relation.FATHER -> father
        Relation.MOTHER -> mother
        Relation.BROTHER -> brother
        Relation.SISTER -> sister
        Relation.SON -> son
        Relation.DAUGHTER -> daughter
        Relation.SPOUSE -> spouse
        Relation.UNCLE_PATERNAL -> unclePaternal
        Relation.AUNT_PATERNAL -> auntPaternal
        Relation.COUSIN_PATERNAL -> cousinPaternal
        Relation.UNCLE_MATERNAL -> uncleMaternal
        Relation.AUNT_MATERNAL -> auntMaternal
        Relation.COUSIN_MATERNAL -> cousinMaternal
        Relation.IN_LAW -> inLaw
        Relation.CLOSE_FRIEND -> closeFriend
        Relation.CUSTOM -> custom
    }
}
