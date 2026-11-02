import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبارات منطق العرض النقي لشاشة جهات الاتصال (T4 — Issue #25): مطابقة سلوك
 * التجريب الحرفي وحالات الشاشة ومنتقي الأرقام مع مرآة Compose (T3 — PR #36).
 */
final class ContactsPresentationLogicTests: XCTestCase {

    /// المصنع الوحيد لبيانات الاختبار — منشئات Kotlin ذات المعاملات الافتراضية
    /// لا تُصدَّر لـ Swift (توثيق: [ContactsTestFixtures])، والقيم الصريحة أدناه
    /// تطابق افتراضيات Kotlin حرفياً (isLoading=true، searchQuery=""، syncState=.idle).
    private let fixtures = ContactsTestFixtures.shared

    private func contact(_ id: Int64, _ name: String) -> Contact {
        fixtures.createContact(
            id: id,
            systemContactId: "sys-\(id)",
            name: fixtures.createContactName(displayName: name)
        )
    }

    // MARK: - التجميع الحرفي

    func testLetterGroupingMergesNonLetterNamesAcrossUnicodeRanges() {
        // مراجعة PR #36 — F2: أرقام غربية (قبل اللاتينية) وأرقام هندية-عربية
        // (بعد العربية) كلاها غير حرفية ⇒ رأس «#» واحد لا رأسان.
        let contacts = [
            contact(1, "123 Services"),
            contact(2, "أحمد"),
            contact(3, "Ziad"),
            contact(4, "٣مشغل"),
        ]
        let sections = buildContactsLetterSections(from: contacts)

        XCTAssertEqual(sections.map(\.letter), ["#", "أ", "Z"])
        XCTAssertEqual(sections[0].contacts.map(\.id), [1, 4])
        XCTAssertEqual(sections[1].contacts.map(\.id), [2])
        XCTAssertEqual(sections[2].contacts.map(\.id), [3])
    }

    func testLetterGroupingPreservesFirstAppearanceOrder() {
        let contacts = [
            contact(1, "سارة"),
            contact(2, "Ahmed"),
            contact(3, "سليم"),
            contact(4, "Basil"),
        ]
        let sections = buildContactsLetterSections(from: contacts)

        XCTAssertEqual(sections.map(\.letter), ["س", "A", "B"])
        XCTAssertEqual(sections[0].contacts.count, 2)
    }

    func testGroupingLetterUppercasingEmptyAndNonLetter() {
        XCTAssertEqual(contactsGroupingLetter(contact(1, "ahmed")), "A")
        XCTAssertEqual(contactsGroupingLetter(contact(2, "  ")), "#")
        XCTAssertEqual(contactsGroupingLetter(contact(3, "9 plumber")), "#")
        XCTAssertEqual(contactsInitialLetter(contact(4, "منى")), "م")
        // مرآة initialOf في Compose (agy — F2): الصورة الرمزية تعرض المحرف
        // الأول كما هو حتى مع الأرقام/الرموز — التجميع تحت «#» شأن الرؤوس.
        XCTAssertEqual(contactsInitialLetter(contact(5, "9 plumber")), "9")
        XCTAssertEqual(contactsInitialLetter(contact(6, "  ")), "#")
    }

    // MARK: - أولوية حالات الشاشة (مرآة ترتيب when في ContactsContent)

    func testScreenPhasePermissionDeniedOverridesEverything() {
        let state = fixtures.createContactsUiState(
            isLoading: true, searchQuery: "", contacts: [contact(1, "أحمد")],
            syncState: ContactsSyncState.permissionDenied
        )
        XCTAssertEqual(contactsScreenPhase(for: state), .permissionDenied)
    }

    func testScreenPhaseErrorOnlyWithEmptyList() {
        XCTAssertEqual(
            contactsScreenPhase(
                for: fixtures.createContactsUiState(
                    isLoading: true, searchQuery: "", contacts: [],
                    syncState: ContactsSyncState.failed
                )
            ),
            .error
        )
        // خطأ مع بيانات مرئية ⇒ المحتوى يبقى (البيانات المحلية مقروءة — ADR-0010 §5).
        XCTAssertEqual(
            contactsScreenPhase(
                for: fixtures.createContactsUiState(
                    isLoading: true, searchQuery: "", contacts: [contact(1, "أحمد")],
                    syncState: ContactsSyncState.failed
                )
            ),
            .content
        )
        XCTAssertEqual(
            contactsScreenPhase(
                for: fixtures.createContactsUiState(
                    isLoading: true, searchQuery: "", contacts: [],
                    syncState: ContactsSyncState.ready
                )
            ),
            .content
        )
    }

    // MARK: - حالات منطقة القائمة (مرآة ContactsListArea)

    func testListPhases() {
        XCTAssertEqual(
            contactsListPhase(
                for: fixtures.createContactsUiState(
                    isLoading: true, searchQuery: "", contacts: [],
                    syncState: ContactsSyncState.idle
                )
            ),
            .loading
        )
        XCTAssertEqual(
            contactsListPhase(
                for: fixtures.createContactsUiState(
                    isLoading: false, searchQuery: "سل", contacts: [],
                    syncState: ContactsSyncState.idle
                )
            ),
            .noResults
        )
        XCTAssertEqual(
            contactsListPhase(
                for: fixtures.createContactsUiState(
                    isLoading: false, searchQuery: "", contacts: [],
                    syncState: ContactsSyncState.idle
                )
            ),
            .empty
        )
        XCTAssertEqual(
            contactsListPhase(
                for: fixtures.createContactsUiState(
                    isLoading: true, searchQuery: "", contacts: [contact(1, "أحمد")],
                    syncState: ContactsSyncState.idle
                )
            ),
            .loaded
        )
    }

    // MARK: - منتقي الأرقام (مرآة dispatchResolved)

    func testNumberPickerIntentMapping() {
        let call = contactsNumberPickerIntent(
            contactId: 7,
            e164: "+201000000000",
            action: ContactSwipeAction.call
        )
        XCTAssertTrue(call is ContactsIntentCallNumber)
        XCTAssertEqual((call as? ContactsIntentCallNumber)?.e164, "+201000000000")

        let message = contactsNumberPickerIntent(
            contactId: 7,
            e164: "+201000000000",
            action: ContactSwipeAction.message
        )
        XCTAssertTrue(message is ContactsIntentMessage)
    }

    // MARK: - قوالب العد

    func testCountTemplateFormatting() {
        XCTAssertEqual(formatCountTemplate("تم تحديد %d", 12), "تم تحديد 12")
        XCTAssertEqual(formatCountTemplate("%d selected", 3), "3 selected")
    }

    // MARK: - ترميز tel:/sms: (مرآة Uri.encode — مراجعة agy — F1)

    func testE164EncodingKeepsInternationalAndPlainNumbersLiteral() {
        // الصيغة الدولية القياسية: + يبقى حرفياً، والأرقام كما هي —
        // لا تحويل غير ضروري لأرقام E.164 الصالحة.
        XCTAssertEqual(e164PercentEncoded("+201100123456"), "+201100123456")
        XCTAssertEqual(e164PercentEncoded("5551234"), "5551234")
        // الفواصل الشائعة في بيانات المنتج تبقى حرفية.
        XCTAssertEqual(e164PercentEncoded("+1(555)123-4567"), "+1(555)123-4567")
    }

    func testE164EncodingEncodesUSSDAndWhitespace() {
        // * و # حرفياً يُفسَّر # فاصل fragment في RFC 3986 ويُقتطع من الرقم
        // (تحقق تجريبي: tel:*123# → path=*123 + fragment=#). الترميز إلزامي.
        XCTAssertEqual(e164PercentEncoded("*123#"), "%2A123%23")
        XCTAssertEqual(e164PercentEncoded("+1555*123#"), "+1555%2A123%23")
        XCTAssertEqual(e164PercentEncoded("+1555 1234"), "+1555%201234")
        XCTAssertEqual(e164PercentEncoded(",1234"), "%2C1234")
        // المدخل الفارغ يبقى فارغاً — الراوتر يحرسه قبل بناء URL.
        XCTAssertEqual(e164PercentEncoded(""), "")
    }

    func testEncodedE164URLHasNoFragmentAndPreservesNumber() {
        // الحسم: الرقم المرمّز ينتج URL بلا fragment، والمسار يعود
        // (بعد فك الترميز) مطابقاً للرقم المطلوب — للاتصال والرسائل.
        let dialURL = URL(string: "tel:\(e164PercentEncoded("+1555*123#"))")
        XCTAssertEqual(dialURL?.absoluteString, "tel:+1555%2A123%23")
        XCTAssertNil(dialURL?.fragment, "# must be percent-encoded, not a fragment delimiter")

        let smsURL = URL(string: "sms:\(e164PercentEncoded("*123#"))")
        XCTAssertEqual(smsURL?.absoluteString, "sms:%2A123%23")
        XCTAssertNil(smsURL?.fragment)

        // أرقام E.164 الصالحة تُنتج URL سليماً بلا fragment أصلاً.
        let intl = URL(string: "tel:\(e164PercentEncoded("+201100123456"))")
        XCTAssertEqual(intl?.absoluteString, "tel:+201100123456")
        XCTAssertNil(intl?.fragment)
    }
}
