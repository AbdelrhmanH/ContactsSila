import Foundation
import SharedLogic

/**
 * نصوص شاشة الإعدادات والتفضيلات لـ SwiftUI (T9 — Issue #30) — مصدرها حصرياً
 * r.lproj/Localizable.strings (الافتراضية) و en.lproj/Localizable.strings
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 */
/**
 * تنسيق الوقت لشاشة الإعدادات iOS — مرآة صادقة لدلالة Compose الشائعة
 * (`padStart(2, '0')` على الساعة والدقيقة): «07:05-» إلخ. نقي وقابل للاختبار.
 */
enum SettingsTimeFormatting {

    /// رقمان برتبة ثابتة (تطابق `padStart(2, '0')`).
    static func twoDigits(_ value: Int) -> String {
        String(format: "%02d", value)
    }

    /// «ساعة:دقيقة مئولةً» لصف موعد التذكير.
    static func hourMinute(hour: Int, minute: Int) -> String {
        "\(twoDigits(hour)):\(twoDigits(minute))"
    }

    /// «XX:00 - YY:00» لصف ساعات الهدوء (دقة ساعة كما في الحوار المشترك).
    static func quietRange(startHour: Int, endHour: Int) -> String {
        "\(twoDigits(startHour)):00 - \(twoDigits(endHour)):00"
    }
}

struct SettingsLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    var screenTitle: String { string("settings_title") }
    var sectionAppearance: String { string("settings_section_appearance") }
    var theme: String { string("settings_theme") }
    var themeLight: String { string("settings_theme_light") }
    var themeDark: String { string("settings_theme_dark") }
    var themeSystem: String { string("settings_theme_system") }
    var languageTitle: String { string("settings_language") }
    /// أسماء اللغات تُعرض دوماً بلغة نفسها في كل اللغات (نمط النظام القياسي) —
    /// قيمتان ثابتتان في جدولي ar/en معاً لضمان لا نص حرفي في الكود.
    var languageArabicName: String { string("settings_language_name_arabic") }
    var languageEnglishName: String { string("settings_language_name_english") }
    var digits: String { string("settings_digits") }
    var digitsArabic: String { string("settings_digits_arabic") }
    var digitsHindi: String { string("settings_digits_hindi") }
    var nameOrder: String { string("settings_name_order") }
    var nameOrderFirstLast: String { string("settings_name_order_first_last") }
    var nameOrderLastFirst: String { string("settings_name_order_last_first") }

    var sectionGeneral: String { string("settings_section_general") }
    var defaultTab: String { string("settings_default_tab") }
    var tabContacts: String { string("settings_tab_contacts") }
    var tabServices: String { string("settings_tab_services") }
    var tabArham: String { string("settings_tab_arham") }
    var swipeStart: String { string("settings_swipe_start") }
    var swipeEnd: String { string("settings_swipe_end") }
    var swipeCall: String { string("settings_swipe_call") }
    var swipeMessage: String { string("settings_swipe_message") }
    var swipeNone: String { string("settings_swipe_none") }
    var simSlot: String { string("settings_sim_slot") }
    var simAskAlways: String { string("settings_sim_ask_always") }
    var simSlot1: String { string("settings_sim_slot_1") }
    var simSlot2: String { string("settings_sim_slot_2") }

    var sectionArham: String { string("settings_section_arham") }
    var arhamNotifications: String { string("settings_arham_notifications") }
    var arhamNotificationTime: String { string("settings_arham_notification_time") }
    var arhamMaxPerDay: String { string("settings_arham_max_per_day") }
    var quietHours: String { string("settings_quiet_hours") }
    var quietHoursStart: String { string("settings_quiet_hours_start") }
    var quietHoursEnd: String { string("settings_quiet_hours_end") }

    var sectionOrganization: String { string("settings_section_organization") }
    var smartFilter: String { string("settings_smart_filter") }
    var duplicateScan: String { string("settings_duplicate_scan") }
    var customKeywords: String { string("settings_custom_keywords") }
    var addKeyword: String { string("settings_add_keyword") }

    var sectionRingtones: String { string("settings_section_ringtones") }
    var ringtoneIosNotice: String { string("settings_ringtone_ios_notice") }
    var openContactsApp: String { string("settings_open_contacts_app") }

    var sectionBackup: String { string("settings_section_backup") }
    var backupDescription: String { string("settings_backup_description") }
    var exportBackup: String { string("settings_export_backup") }
    var importBackup: String { string("settings_import_backup") }
    var backupPassword: String { string("settings_backup_password") }
    var backupPasswordHint: String { string("settings_backup_password_hint") }
    var restorePasswordHint: String { string("settings_restore_password_hint") }
    var restoreModeMerge: String { string("settings_restore_mode_merge") }
    var restoreModeReplace: String { string("settings_restore_mode_replace") }
    var restoreModeTitle: String { string("settings_restore_mode_title") }
    var restoreModeMergeDesc: String { string("settings_restore_mode_merge_desc") }
    var restoreModeReplaceDesc: String { string("settings_restore_mode_replace_desc") }
    var restorePreferences: String { string("settings_restore_preferences") }
    var restoreDialogDesc: String { string("settings_restore_desc") }
    var previewDialogTitle: String { string("settings_preview_title") }
    var previewButton: String { string("settings_preview_button") }
    var previewCountsFormat: String { string("settings_preview_counts") }
    var startRestore: String { string("settings_start_restore") }
    var backupFileEmpty: String { string("settings_backup_file_empty") }
    var backupFileReadFailed: String { string("settings_backup_file_read_failed") }
    var backupErrorBlankPassword: String { string("settings_backup_error_blank_password") }
    var backupErrorDecryptionFailed: String { string("settings_backup_error_decryption_failed") }
    var backupErrorNotConfigured: String { string("settings_backup_error_not_configured") }
    var backupErrorGeneric: String { string("settings_backup_error_generic") }
    var restoreDoneFormat: String { string("settings_restore_done") }

    // نتائج الاستعادة والربط اليدوي (S1-15 — تذكرة #62)
    var restoreResultTitle: String { string("settings_restore_result_title") }
    var restoreResultCountsFormat: String { string("settings_restore_result_counts") }
    var restoreUnmatchedRemainingFormat: String { string("settings_restore_unmatched_remaining") }
    var linkManualButton: String { string("settings_link_manual_button") }
    var linkSearchHint: String { string("settings_link_search_hint") }
    var linkNoCandidates: String { string("settings_link_no_candidates") }
    var linkFailedLabel: String { string("settings_link_failed_label") }
    var linkAllDone: String { string("settings_link_all_done") }
    var manuallyLinkedFormat: String { string("settings_manually_linked_count") }
    var resultDone: String { string("action_done") }
    var linkedToFormat: String { string("settings_link_dialog_title") }

    /// تسمية التصنيف من مفاتيح category_* المشتركة (لا تكرار في الموارد)
    func categoryLabel(_ category: SharedLogic.Category) -> String {
        switch category {
        case .family: return string("category_family")
        case .friends: return string("category_friends")
        case .work: return string("category_work")
        case .services: return string("category_services")
        case .emergency: return string("category_emergency")
        case .uncategorized: return string("category_uncategorized")
        @unknown default: return string("category_uncategorized")
        }
    }

    /// تسمية علاقة الرحم من مفاتيح arham_relation_* المشتركة (لا تكرار في الموارد)
    func relationLabel(_ relation: Relation) -> String {
        switch relation {
        case .grandfather: return string("arham_relation_grandfather")
        case .grandmother: return string("arham_relation_grandmother")
        case .father: return string("arham_relation_father")
        case .mother: return string("arham_relation_mother")
        case .brother: return string("arham_relation_brother")
        case .sister: return string("arham_relation_sister")
        case .son: return string("arham_relation_son")
        case .daughter: return string("arham_relation_daughter")
        case .spouse: return string("arham_relation_spouse")
        case .unclePaternal: return string("arham_relation_uncle_paternal")
        case .auntPaternal: return string("arham_relation_aunt_paternal")
        case .uncleMaternal: return string("arham_relation_uncle_maternal")
        case .auntMaternal: return string("arham_relation_aunt_maternal")
        case .cousinPaternal: return string("arham_relation_cousin_paternal")
        case .cousinMaternal: return string("arham_relation_cousin_maternal")
        case .inLaw: return string("arham_relation_in_law")
        case .closeFriend: return string("arham_relation_close_friend")
        case .custom: return string("arham_relation_custom")
        @unknown default: return string("arham_relation_custom")
        }
    }

    /// قيم السجل غير المطابق (تصنيف وصلة رحم) مفصولة عمودياً
    func unmatchedEntryLabel(_ entry: UnmatchedBackupEntry) -> String {
        let parts: [String] = [
            entry.category.map(categoryLabel),
            entry.arhamRelation.map(relationLabel)
        ].compactMap { $0 }
        return parts.joined(separator: " · ")
    }

    var sectionAbout: String { string("settings_section_about") }
    var appVersion: String { string("settings_app_version") }
    var offlineNotice: String { string("settings_offline_first_notice") }
    var cancel: String { string("action_cancel") }
    var save: String { string("action_save") }
    var confirm: String { string("action_confirm") }
}