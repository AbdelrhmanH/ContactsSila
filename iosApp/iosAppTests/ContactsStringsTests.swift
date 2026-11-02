import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبار اكتمال مفاتيح موارد الترجمة لشاشة جهات الاتصال (T4 — Issue #25):
 * كل مفتاح يستهلكه [ContactsLocalizedStrings] وورقة الإضافة الذكية يجب أن
 * يكون موجوداً فعلاً في `ar.lproj` و`en.lproj` داخل حزمة التطبيق المضيفة —
 * لا نصوص ثابتة في الكود (AGENTS.md) ولا مفاتيح مفقودة تنزل بصمت للمفتاح.
 */
final class ContactsStringsTests: XCTestCase {

    /// المفاتيح المستهلكة فعلياً من ContactsStrings.swift وContentView.swift.
    private let requiredKeys = [
        "contacts_title", "contacts_search_hint", "contacts_loading",
        "contacts_empty_title", "contacts_empty_body",
        "contacts_empty_search_title", "contacts_empty_search_body",
        "contacts_permission_denied_title", "contacts_permission_denied_body",
        "contacts_open_settings", "contacts_error_title", "contacts_error_body",
        "contacts_retry", "contacts_suggestion_duplicates",
        "contacts_suggestion_smart_filter", "contacts_suggestion_dismiss",
        "contacts_selection_title", "contacts_bulk_categorize",
        "contacts_bulk_favorite", "contacts_bulk_unfavorite", "contacts_done",
        "contacts_delete_confirm_title", "contacts_delete_confirm_body",
        "contacts_select_hint", "contacts_number_picker_title",
        "contacts_account_all", "contacts_account_device",
        "contacts_account_google", "contacts_account_other",
        "contacts_limited_banner",
        "smart_add_title", "smart_add_new_contact", "smart_add_family",
        "smart_add_service", "smart_add_qr",
        "action_call", "action_message", "action_cancel", "action_share",
        "action_delete", "action_confirm",
        "category_all", "category_family", "category_friends", "category_work",
        "category_services", "category_emergency", "category_uncategorized",
    ]

    private var appBundle: Bundle { Bundle.main } // الاختبار مستضاف داخل التطبيق

    func testAllKeysExistInBothLocalizations() throws {
        for language in ["ar", "en"] {
            let lprojPath = try XCTUnwrap(
                appBundle.path(forResource: language, ofType: "lproj"),
                "Missing \(language).lproj in the hosted app bundle"
            )
            let stringsPath = try XCTUnwrap(
                NSDictionary(contentsOfFile: lprojPath + "/Localizable.strings") as? [String: String],
                "Unreadable Localizable.strings for \(language)"
            )
            for key in requiredKeys {
                let value = stringsPath[key]
                XCTAssertNotNil(value, "Missing key \(key) in \(language).lproj/Localizable.strings")
                XCTAssertFalse(value?.isEmpty ?? true, "Empty value for \(key) in \(language)")
            }
        }
    }

    func testCountTemplatesContainPlaceholder() throws {
        for language in ["ar", "en"] {
            let lprojPath = try XCTUnwrap(appBundle.path(forResource: language, ofType: "lproj"))
            let strings = try XCTUnwrap(
                NSDictionary(contentsOfFile: lprojPath + "/Localizable.strings") as? [String: String]
            )
            for key in ["contacts_suggestion_duplicates", "contacts_suggestion_smart_filter",
                        "contacts_selection_title", "contacts_delete_confirm_body"] {
                XCTAssertTrue(
                    strings[key]?.contains("%d") ?? false,
                    "\(key) in \(language) must contain %d for count formatting"
                )
            }
        }
    }

    @MainActor
    func testLocalizedStringsResolveThroughSharedLanguage() {
        // اللغة من الحالة المشتركة نفسها — نفس مسار الإنتاج بالتمام.
        let arabic = ContactsLocalizedStrings(language: AppLanguage.arabic)
        XCTAssertNotEqual(arabic.screenTitle, "contacts_title")
        XCTAssertNotEqual(arabic.limitedBanner, "contacts_limited_banner")

        let english = ContactsLocalizedStrings(language: AppLanguage.english)
        XCTAssertNotEqual(english.screenTitle, "contacts_title")
        XCTAssertEqual(english.categoryTitle(SharedLogic.Category.services), "Services")

        XCTAssertEqual(arabic.selectionTitle(5), "تم تحديد 5")
        XCTAssertEqual(english.deleteConfirmationMessage(2), "2 contacts will be permanently removed from Sila.")
    }
}
