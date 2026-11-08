import Foundation
import SharedLogic

/**
 * نصوص شاشة الإعدادات والتفضيلات لـ SwiftUI (T9 — Issue #30) — مصدرها حصرياً
 * r.lproj/Localizable.strings (الافتراضية) و en.lproj/Localizable.strings
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 */
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
    var restoreDoneFormat: String { string("settings_restore_done") }

    var sectionAbout: String { string("settings_section_about") }
    var appVersion: String { string("settings_app_version") }
    var offlineNotice: String { string("settings_offline_first_notice") }
    var cancel: String { string("action_cancel") }
    var save: String { string("action_save") }
    var confirm: String { string("action_confirm") }
}