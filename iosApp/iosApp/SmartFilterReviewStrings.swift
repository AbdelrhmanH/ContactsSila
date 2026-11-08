import Foundation
import SharedLogic

/**
 * نصوص شاشة مراجعة الفلترة الذكية لـ SwiftUI (T7 — Issue #28) — مصدرها حصرياً
 * `ar.lproj/Localizable.strings` و `en.lproj/Localizable.strings` حسب لغة الحالة
 * المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md). المرآة النصية لعقد
 * `SmartFilterReviewStrings.kt` بنفس المصطلحات العربية/الإنجليزية.
 */
struct SmartFilterReviewLocalizedStrings {

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

    var screenTitle: String { string("sf_screen_title") }
    var backDescription: String { string("sf_back_description") }
    var loading: String { string("sf_loading") }
    var emptyTitle: String { string("sf_empty_title") }
    var emptyMessage: String { string("sf_empty_message") }

    var confidenceHigh: String { string("sf_confidence_high") }
    var confidenceMedium: String { string("sf_confidence_medium") }
    var confidenceLow: String { string("sf_confidence_low") }

    func evidenceLabel(_ token: String) -> String {
        string("sf_evidence_label").replacingOccurrences(of: "%s", with: token)
    }

    func groupCount(_ count: Int32) -> String { countTemplate("sf_group_count")(count) }
    var selectAll: String { string("sf_select_all") }
    var unselectAll: String { string("sf_unselect_all") }

    func applySelected(_ count: Int32) -> String { countTemplate("sf_apply_selected")(count) }
    var notThisCategory: String { string("sf_not_this_category") }
    var neverAsk: String { string("sf_never_ask") }
    var errorApply: String { string("sf_error_apply") }
    var errorContactNotFound: String { string("sf_error_not_found") }
    var errorArhamLink: String { string("sf_error_arham_link") }

    func arhamOfferTitle(_ count: Int32) -> String {
        string("sf_arham_offer_title").replacingOccurrences(of: "%d", with: String(count))
    }
    var arhamOfferAccept: String { string("sf_arham_offer_accept") }
    var cancel: String { string("action_cancel") }

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
}
