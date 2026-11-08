import Foundation
import SharedLogic

/**
 * نصوص شاشة مراجعة المكررات لـ SwiftUI (T7 — Issue #28) — مصدرها حصرياً
 * `ar.lproj/Localizable.strings` (الافتراضية) و `en.lproj/Localizable.strings` حسب
 * لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md). المرآة النصية
 * لعقد `DuplicatesReviewStrings.kt` بنفس المصطلحات العربية/الإنجليزية.
 */
struct DuplicatesReviewLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    private func countTemplate(_ key: String) -> (Int32) -> String {
        let value = string(key)
        return { formatCountTemplate(value, $0) }
    }

    var screenTitle: String { string("dup_screen_title") }
    var backDescription: String { string("dup_back_description") }
    var loading: String { string("dup_loading") }
    var emptyTitle: String { string("dup_empty_title") }
    var emptyMessage: String { string("dup_empty_message") }

    var confidenceHigh: String { string("dup_confidence_high") }
    var confidenceMedium: String { string("dup_confidence_medium") }
    var confidenceLow: String { string("dup_confidence_low") }

    /// قالب الدليل يحمل «%s» (قيمة خام: رقم/بريد/اسم) — تعويض حرفي كمرآة Compose.
    func reasonLabel(kind: DuplicateReasonKind, detail: String) -> String {
        let key: String
        switch kind {
        case .samePhone: key = "dup_reason_same_phone"
        case .sameEmail: key = "dup_reason_same_email"
        case .sameName: key = "dup_reason_same_name"
        case .similarName: key = "dup_reason_similar_name"
        @unknown default: key = "dup_reason_same_name"
        }
        return string(key).replacingOccurrences(of: "%s", with: detail)
    }

    var previewTitle: String { string("dup_preview_title") }

    func previewSummary(phones: Int32, emails: Int32) -> String {
        replacingFirst(replacingFirst(string("dup_preview_summary"), "%d", String(phones)), "%d", String(emails))
    }

    var previewNotesMerged: String { string("dup_preview_notes_merged") }
    var ringtoneConflictNotice: String { string("dup_ringtone_conflict") }
    var mergeButton: String { string("dup_merge_button") }
    var notDuplicatesButton: String { string("dup_not_duplicates_button") }
    var mergeAllConfirmed: (Int32) -> String { countTemplate("dup_merge_all_confirmed") }
    var undoMessage: String { string("dup_undo_message") }
    var undo: String { string("action_undo") }
    var callMemberLabel: String { string("dup_call_member") }
    var openMemberDescription: String { string("dup_open_member_description") }
    var errorMerge: String { string("dup_error_merge") }
    var errorUndo: String { string("dup_error_undo") }

    var categoryTitle: (SharedLogic.Category) -> String {
        let family = string("category_family")
        let friends = string("category_friends")
        let work = string("category_work")
        let services = string("category_services")
        let emergency = string("category_emergency")
        let uncategorized = string("category_uncategorized")
        return { category in
            switch category {
            case .family: return family
            case .friends: return friends
            case .work: return work
            case .services: return services
            case .emergency: return emergency
            case .uncategorized: return uncategorized
            @unknown default: return uncategorized
            }
        }
    }

    /// تعويض أول ظهور حصراً — لقوالب ذات عائدين (الأرقام ثم البريد)، كمرآة replaceFirst.
    private func replacingFirst(_ template: String, _ needle: String, _ replacement: String) -> String {
        guard let range = template.range(of: needle) else { return template }
        return template.replacingCharacters(in: range, with: replacement)
    }
}
