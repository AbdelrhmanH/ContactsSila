import Foundation
import SharedLogic

/**
 * نصوص شاشتي التفاصيل/التحرير لـ SwiftUI (T6 — Issue #27) — مصدرها حصرياً
 * `ar.lproj/Localizable.strings` (الافتراضية) و `en.lproj/Localizable.strings`
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 * نمط [ContactsLocalizedStrings] من T4 حرفياً — مرآة نصية لعقدي
 * `ContactDetailsStrings.kt` / `ContactEditStrings.kt` بنفس المصطلحات.
 */
struct ContactDetailsLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    /// قراءة مفتاح خام (لاتجاهات التفاعل وغيرها المحسوبة في مكان العرض النقي).
    func stringForKey(_ key: String) -> String { string(key) }

    // الشريط العلوي والأفعال
    var back: String { string("details_back") }
    var edit: String { string("details_edit") }
    var favorite: String { string("details_favorite") }
    var unfavorite: String { string("details_unfavorite") }
    var more: String { string("details_more") }
    var call: String { string("details_call") }
    var message: String { string("details_message") }
    var email: String { string("details_email") }
    var copyNumber: String { string("details_copy_number") }

    // الأقسام
    var phonesSection: String { string("details_phones_section") }
    var appsSection: String { string("details_apps_section") }
    var arhamSection: String { string("details_arham_section") }
    var historySection: String { string("details_history_section") }
    var emailsSection: String { string("details_emails_section") }
    var addressesSection: String { string("details_addresses_section") }
    var datesSection: String { string("details_dates_section") }
    var companySection: String { string("details_company_section") }
    var notesSection: String { string("details_notes_section") }
    var customFieldsSection: String { string("details_custom_fields_section") }
    var websitesSection: String { string("details_websites_section") }
    var accountSection: String { string("details_account_section") }
    var accountDevice: String { string("details_account_device") }

    // الأنواع
    var typeMobile: String { string("details_type_mobile") }
    var typeHome: String { string("details_type_home") }
    var typeWork: String { string("details_type_work") }
    var typeOther: String { string("details_type_other") }
    var emailPersonal: String { string("details_email_personal") }

    // التطبيقات المتصلة (المعرفات المغلقة نفسها في العقد المشترك)
    var appWhatsapp: String { string("details_app_whatsapp") }
    var appTelegram: String { string("details_app_telegram") }
    var appSignal: String { string("details_app_signal") }

    // أرحام
    var freqDaily: String { string("details_freq_daily") }
    var freqWeekly: String { string("details_freq_weekly") }
    var freqBiweekly: String { string("details_freq_biweekly") }
    var freqMonthly: String { string("details_freq_monthly") }
    var freqOccasions: String { string("details_freq_occasions") }

    // الحذف والتراجع (J-5)
    var deleteUndoMessage: String { string("details_delete_undo_message") }
    var undo: String { string("action_undo") }
    var actionShareVCard: String { string("details_action_share_vcard") }
    var actionChangeCategory: String { string("details_action_change_category") }
    var actionDelete: String { string("details_action_delete") }
    var notFound: String { string("details_not_found") }
    var deleteError: String { string("details_delete_error") }
    var loading: String { string("details_loading") }
    var cancel: String { string("action_cancel") }

    // التوقيت النسبي لسجل التواصل (مرآة details_time_*)
    var timeNow: String { string("details_time_now") }
    var minuteOne: String { string("details_time_minutes_one") }
    var minuteTwo: String { string("details_time_minutes_two") }
    var minuteFew: String { string("details_time_minutes_few") }
    var minuteMany: String { string("details_time_minutes_many") }
    var hourOne: String { string("details_time_hours_one") }
    var hourTwo: String { string("details_time_hours_two") }
    var hourFew: String { string("details_time_hours_few") }
    var hourMany: String { string("details_time_hours_many") }
    var timeYesterday: String { string("details_time_yesterday") }

    /// أسماء الأشهر ١–١٢ — أجزاء التاريخ المحلي للتوقيت الأقدم من أمس.
    var months: [String] {
        (1...12).map { string("details_month_\($0)") }
    }

    func phoneTypeName(_ type: String) -> String {
        switch type {
        case "MOBILE": return typeMobile
        case "HOME": return typeHome
        case "WORK": return typeWork
        case "OTHER": return typeOther
        case "PERSONAL": return emailPersonal
        default: return type
        }
    }

    func emailTypeName(_ type: String) -> String {
        switch type {
        case "PERSONAL": return emailPersonal
        case "HOME": return typeHome
        case "WORK": return typeWork
        default: return typeOther
        }
    }

    func addressTypeName(_ type: String) -> String {
        switch type {
        case "HOME": return typeHome
        case "WORK": return typeWork
        default: return typeOther
        }
    }

    func dateTypeName(_ type: String) -> String {
        switch type {
        case "BIRTHDAY": return string("details_date_birthday")
        case "ANNIVERSARY": return string("details_date_anniversary")
        default: return string("details_date_custom")
        }
    }

    func connectedAppName(_ id: String) -> String {
        switch id {
        case "whatsapp": return appWhatsapp
        case "telegram": return appTelegram
        case "signal": return appSignal
        default: return id
        }
    }

    func arhamFrequencyLabel(_ frequency: SharedLogic.ArhamFrequency) -> String {
        switch frequency {
        case .daily: return freqDaily
        case .weekly: return freqWeekly
        case .biweekly: return freqBiweekly
        case .monthly: return freqMonthly
        case .occasions: return freqOccasions
        default: return freqOccasions
        }
    }

    /// أيام آخر تواصل أرحام — صيغ العدد العربية (مرآة details_arham_days_*).
    func arhamDaysLabel(_ days: Int64) -> String {
        let key: String
        switch days {
        case 0: key = "details_arham_days_today"
        case 1: key = "details_arham_days_one"
        case 2: key = "details_arham_days_two"
        case 3...10: key = "details_arham_days_few"
        default: key = "details_arham_days_many"
        }
        return string(key).replacingOccurrences(of: "%d", with: "\(days)")
    }

    func categoryTitle(_ category: SharedLogic.Category) -> String {
        string(categoryKey(category))
    }

    /// اسم القرابة لصف أرحام — مفاتيح `relation_*` المشتركة مع T5 (كل القيم الثمانية عشر).
    func relationName(_ relation: SharedLogic.Relation) -> String {
        let key: String
        switch relation {
        case .grandfather: key = "relation_grandfather"
        case .grandmother: key = "relation_grandmother"
        case .father: key = "relation_father"
        case .mother: key = "relation_mother"
        case .brother: key = "relation_brother"
        case .sister: key = "relation_sister"
        case .son: key = "relation_son"
        case .daughter: key = "relation_daughter"
        case .spouse: key = "relation_spouse"
        case .unclePaternal: key = "relation_uncle_paternal"
        case .auntPaternal: key = "relation_aunt_paternal"
        case .cousinPaternal: key = "relation_cousin_paternal"
        case .uncleMaternal: key = "relation_uncle_maternal"
        case .auntMaternal: key = "relation_aunt_maternal"
        case .cousinMaternal: key = "relation_cousin_maternal"
        case .inLaw: key = "relation_in_law"
        case .closeFriend: key = "relation_close_friend"
        default: key = "relation_custom"
        }
        return string(key)
    }
}

/**
 * نصوص شاشة الإضافة/التعديل (T6) — نمط [ContactDetailsLocalizedStrings].
 */
struct ContactEditLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    var newTitle: String { string("edit_new_title") }
    var editTitle: String { string("edit_edit_title") }
    var save: String { string("edit_save") }
    var saving: String { string("edit_saving") }
    var discardTitle: String { string("edit_discard_title") }
    var discardMessage: String { string("edit_discard_message") }
    var discard: String { string("edit_discard") }
    var keepEditing: String { string("edit_keep_editing") }
    var addPhoto: String { string("edit_add_photo") }
    var changePhoto: String { string("edit_change_photo") }
    var removePhoto: String { string("edit_remove_photo") }
    var photoCamera: String { string("edit_photo_camera") }
    var photoGallery: String { string("edit_photo_gallery") }
    var categoryLabel: String { string("edit_category_label") }
    var firstNameLabel: String { string("edit_first_name") }
    var lastNameLabel: String { string("edit_last_name") }
    var showMore: String { string("edit_show_more") }
    var showLess: String { string("edit_show_less") }
    var prefixLabel: String { string("edit_prefix") }
    var middleNameLabel: String { string("edit_middle_name") }
    var suffixLabel: String { string("edit_suffix") }
    var nicknameLabel: String { string("edit_nickname") }
    var fileAsLabel: String { string("edit_file_as") }
    var phoneticLabel: String { string("edit_phonetic") }
    var addPhone: String { string("edit_add_phone") }
    var addEmail: String { string("edit_add_email") }
    var addWebsite: String { string("edit_add_website") }
    var notesLabel: String { string("edit_notes") }
    var companyLabel: String { string("edit_company") }
    var departmentLabel: String { string("edit_department") }
    var jobTitleLabel: String { string("edit_job_title") }
    var validationNameOrPhone: String { string("edit_validation_name_or_phone") }
    var categoryPromptTitle: String { string("edit_category_prompt_title") }
    var categoryPromptLater: String { string("edit_category_prompt_later") }
    var arhamBirthdayHint: String { string("edit_arham_birthday_hint") }
    var saveError: String { string("edit_save_error") }
    var notFound: String { string("details_not_found") }
    var cancel: String { string("action_cancel") }
    var phonesLabel: String { string("details_phones_section") }
    var addFavorite: String { string("details_favorite") }
    var removeFavorite: String { string("details_unfavorite") }
    var datesLabel: String { string("edit_dates_section") }
    var addressesLabel: String { string("edit_addresses_section") }
    var customFieldsLabel: String { string("edit_custom_fields_section") }
    var addDate: String { string("edit_add_date") }
    var addAddress: String { string("edit_add_address") }
    var addCustomField: String { string("edit_add_custom_field") }
    var removeRow: String { string("edit_remove_row") }
    var monthLabel: String { string("edit_month") }
    var dayLabel: String { string("edit_day") }
    var yearLabel: String { string("edit_year") }
    var streetLabel: String { string("edit_street") }
    var cityLabel: String { string("edit_city") }
    var regionLabel: String { string("edit_region") }
    var postalCodeLabel: String { string("edit_postal_code") }
    var countryLabel: String { string("edit_country") }
    var customFieldLabelLabel: String { string("edit_custom_field_label") }
    var customFieldValueLabel: String { string("edit_custom_field_value") }

    func dateTypeName(_ type: String) -> String {
        switch type {
        case "BIRTHDAY": return string("details_date_birthday")
        case "ANNIVERSARY": return string("details_date_anniversary")
        default: return string("details_date_custom")
        }
    }

    func categoryTitle(_ category: SharedLogic.Category) -> String {
        string(categoryKey(category))
    }

    /// تحذير التكرار غير المانع (J-9) — القالب بنسبة %s.
    func duplicateWarning(_ existingName: String) -> String {
        string("edit_duplicate_warning").replacingOccurrences(of: "%s", with: existingName)
    }

    func phoneTypeName(_ type: String) -> String {
        switch type {
        case "MOBILE": return string("details_type_mobile")
        case "HOME": return string("details_type_home")
        case "WORK": return string("details_type_work")
        default: return string("details_type_other")
        }
    }
}

/// مفتاح ترجمة التصنيف — نفس مفاتيح `category_*` المشتركة مع T3 (نمط مرآة).
func categoryKey(_ category: SharedLogic.Category) -> String {
    switch category {
    case .family: return "category_family"
    case .friends: return "category_friends"
    case .work: return "category_work"
    case .services: return "category_services"
    case .emergency: return "category_emergency"
    default: return "category_uncategorized"
    }
}

// MARK: - تنسيق عرض نقي (مرآة ContactDetailsStringsProvider.kt — قابل للاختبار مباشرة)

/**
 * تنسيق زمن التفاعل نسبياً (الآن/دقائق/ساعات/أمس) وبالتاريخ المحلي لما بعده —
 * مرآة `formatRelativeInteractionTime` الأندرويدية (حساب فروق الملي ثانية، ثم
 * أجزاء التاريخ المحلي عبر التقويم الحالي — بلا منطق أعمال، عرض فقط).
 * النصوص تأتي من [strings] (حزمة اللغة الحالية) — لا نص ثابت هنا.
 */
func formatRelativeInteractionTime(
    _ timestampMillis: Int64,
    nowMillis: Int64,
    strings: ContactDetailsLocalizedStrings,
    months: [String]
) -> String {
    let minuteMillis: Int64 = 60_000
    let hourMillis: Int64 = 3_600_000
    let dayMillis: Int64 = 86_400_000
    let diff = max(nowMillis - timestampMillis, 0)

    let scale: (Int64) -> String = { count in
        switch count {
        case 1: return strings.minuteOne
        case 2: return strings.minuteTwo
        case 3...10: return strings.minuteFew.replacingOccurrences(of: "%d", with: "\(count)")
        default: return strings.minuteMany.replacingOccurrences(of: "%d", with: "\(count)")
        }
    }

    switch diff {
    case 0..<minuteMillis:
        return strings.timeNow
    case minuteMillis..<hourMillis:
        return scale(diff / minuteMillis)
    case hourMillis..<dayMillis:
        let hours = diff / hourMillis
        switch hours {
        case 1: return strings.hourOne
        case 2: return strings.hourTwo
        case 3...10: return strings.hourFew.replacingOccurrences(of: "%d", with: "\(hours)")
        default: return strings.hourMany.replacingOccurrences(of: "%d", with: "\(hours)")
        }
    case dayMillis..<(2 * dayMillis):
        return strings.timeYesterday
    default:
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMillis) / 1000.0)
        let calendar = Calendar.current
        let comps = calendar.dateComponents([.day, .month, .year], from: date)
        let currentYear = calendar.component(.year, from: Date())
        let monthName = months.count == 12 ? months[max(comps.month ?? 1, 1) - 1] : ""
        let dayText = "\(comps.day ?? 0)"
        if comps.year == currentYear {
            return "\(dayText) \(monthName)"
        }
        return "\(dayText) \(monthName) \(comps.year ?? 0)"
    }
}

/** مدة المكالمة رقميّة عابرة للغات: `M:SS` — مرآة formatCallDuration. */
func formatCallDuration(_ seconds: Int32) -> String {
    let safe = max(Int(seconds), 0)
    return "\(safe / 60):\(String(format: "%02d", safe % 60))"
}

/** اتجاه التفاعل — مفاتيح details_direction_* (مرآة directionLabel). */
func directionKey(_ direction: CallDirection) -> String {
    switch direction {
    case .in_: return "details_direction_in"
    case .out: return "details_direction_out"
    default: return "details_direction_missed"
    }
}
