import Foundation
import SharedLogic

/**
 * نصوص شاشة جهات الاتصال لـ SwiftUI (T4 — Issue #25) — مصدرها حصرياً
 * `ar.lproj/Localizable.strings` (الافتراضية) و `en.lproj/Localizable.strings`
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 * المرآة النصية لعقد `ContactsStrings.kt` (T3) بنفس المصطلحات العربية/الإنجليزية.
 */
struct ContactsLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    private func template(_ key: String) -> (Int32) -> String {
        let value = string(key)
        return { formatCountTemplate(value, $0) }
    }

    // العنوان والبحث والحالات
    var screenTitle: String { string("contacts_title") }
    var categoryAll: String { string("category_all") }
    var searchPlaceholder: String { string("contacts_search_hint") }
    var emptyContactsTitle: String { string("contacts_empty_title") }
    var emptyContactsMessage: String { string("contacts_empty_body") }
    var emptySearchTitle: String { string("contacts_empty_search_title") }
    var emptySearchMessage: String { string("contacts_empty_search_body") }
    var permissionDeniedTitle: String { string("contacts_permission_denied_title") }
    var permissionDeniedMessage: String { string("contacts_permission_denied_body") }
    var openSettings: String { string("contacts_open_settings") }
    var errorTitle: String { string("contacts_error_title") }
    var errorMessage: String { string("contacts_error_body") }
    var retry: String { string("contacts_retry") }

    // بطاقات الاقتراح
    var suggestionDuplicates: (Int32) -> String { template("contacts_suggestion_duplicates") }
    var suggestionSmartFilter: (Int32) -> String { template("contacts_suggestion_smart_filter") }
    var suggestionDismiss: String { string("contacts_suggestion_dismiss") }

    // شريط التحديد المتعدد
    var selectionTitle: (Int32) -> String { template("contacts_selection_title") }
    var bulkCategorize: String { string("contacts_bulk_categorize") }
    var bulkFavorite: String { string("contacts_bulk_favorite") }
    var bulkUnfavorite: String { string("contacts_bulk_unfavorite") }
    var bulkShare: String { string("action_share") }
    var bulkDelete: String { string("action_delete") }
    var selectionDone: String { string("contacts_done") }

    // تأكيد الحذف
    var deleteConfirmationTitle: String { string("contacts_delete_confirm_title") }
    var deleteConfirmationMessage: (Int32) -> String { template("contacts_delete_confirm_body") }
    var confirmDelete: String { string("action_confirm") }
    var cancel: String { string("action_cancel") }

    // السحب وأدلال الوصول
    var swipeCall: String { string("action_call") }
    var swipeMessage: String { string("action_message") }
    var callRow: String { string("action_call") }
    var selectContact: String { string("contacts_select_hint") }

    // منتقي الأرقام
    var numberPickerTitle: String { string("contacts_number_picker_title") }

    // فلتر الحساب
    var accountFilterAll: String { string("contacts_account_all") }
    var accountFilterDevice: String { string("contacts_account_device") }
    var accountFilterGoogle: String { string("contacts_account_google") }
    var accountFilterOther: String { string("contacts_account_other") }

    // الوصول الجزئي .limited (ADR-0009 — سطح الصلاحية الأصيل iOS 18+)
    var limitedBanner: String { string("contacts_limited_banner") }

    /// أسماء التصنيفات (نفس موارد Compose category_*).
    func categoryTitle(_ category: SharedLogic.Category) -> String {
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

    /// أسماء فلاتر الحساب (نفس `accountFilterLabel` في Compose).
    func accountFilterTitle(_ filter: AccountFilter) -> String {
        switch filter {
        case .all: return accountFilterAll
        case .device: return accountFilterDevice
        case .google: return accountFilterGoogle
        case .other: return accountFilterOther
        @unknown default: return accountFilterAll
        }
    }
}
