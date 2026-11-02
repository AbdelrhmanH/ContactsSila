import Foundation
import SharedLogic

/**
 * منطق العرض النقي لشاشة جهات الاتصال (T4 — Issue #25) — انعكاس حرفي لمنطق
 * `ContactsScreen.kt` (T3) بلا أي منطق أعمال: تجميع القائمة حرفياً، أولوية
 * حالات الشاشة، وبناء Intent منتقي الأرقام. كل الدوال هنا نقية وقابلة للاختبار
 * مباشرة (iosAppTests)، والواجهة لا تحسب شيئاً خارج هذا الملف.
 *
 * الحدود (Issue #25): لا فرز ولا تصفية ولا بحث هنا — القائمة تصل مركّبة من
 * Room عبر `ContactsUiState` المشترك، والبحث بـ Debounce 150ms في Kotlin.
 */

// MARK: - التجميع الحرفي (مرآة buildFlatItems/letterOf في ContactsScreen.kt)

/** مقطع حرفي: رأس الحرف + جهاته — الترتيب بترتيب أول ظهور كما في Compose. */
struct ContactsLetterSection: Identifiable, Equatable {
    let letter: String
    let contacts: [Contact]

    var id: String { letter }
}

/**
 * بناء المقاطع الحرفية من القائمة المرئية: تجميع بالحرف عبر تدفّق واحد محافظ
 * على ترتيب أول ظهور (مرآة `groupBy` في Kotlin عبر LinkedHashMap — مراجعة
 * PR #36 — F2: الترتيب التتابعي كان يُنشئ رأسين لنفس الحرف حين تتوزع الأسماء
 * غير الحرفية «#» على نطاقين يونيكوديين غير متجاورين).
 */
func buildContactsLetterSections(from contacts: [Contact]) -> [ContactsLetterSection] {
    var order: [String] = []
    var buckets: [String: [Contact]] = [:]
    for contact in contacts {
        let letter = contactsGroupingLetter(contact)
        if buckets[letter] == nil {
            order.append(letter)
        }
        buckets[letter, default: []].append(contact)
    }
    return order.map { letter in
        ContactsLetterSection(letter: letter, contacts: buckets[letter] ?? [])
    }
}

/**
 * الحرف الجماعي: أول حرف من الاسم الظاهر بعد قصّ الفراغات؛ غير الحرفي
 * (أرقام/رموز) ⇒ «#». التحويل لحالة الأحرف ثابت الإعدادات (بلا لغة الجهاز)
 * كي يطابق `uppercaseChar()` في Kotlin.
 */
func contactsGroupingLetter(_ contact: Contact) -> String {
    let trimmed = contact.name.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let first = trimmed.first else { return "#" }
    return first.isLetter ? String(first).uppercased() : "#"
}

/**
 * حرف الصورة الرمزية — مرآة `initialOf` في Compose حرفياً (توافق أندرويد —
 * مراجعة agy — F2): أول محرف بعد التقليم كما هو بلا تحويل — الأرقام والرموز
 * تظهر في الصورة الرمزية رغم تجمّعها تحت «#» في رؤوس الأقسام، و«#» للأسامي
 * الفارغة فقط (هذا فرق مقصود عن [contactsGroupingLetter]).
 */
func contactsInitialLetter(_ contact: Contact) -> String {
    let trimmed = contact.name.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let first = trimmed.first else { return "#" }
    return String(first)
}

// MARK: - أولوية حالات الشاشة (مرآة when في ContactsContent)

/** حالة الشاشة العليا — مرآة ترتيب `when` في `ContactsContent` (T3): رفض الصلاحية
 *  يطغى على كل شيء، ثم الخطأ مع قائمة فارغة، وإلا فالمحتوى. */
enum ContactsScreenPhase: Equatable {
    case permissionDenied
    case error
    case content
}

func contactsScreenPhase(for state: ContactsUiState) -> ContactsScreenPhase {
    if state.isPermissionDenied { return .permissionDenied }
    if state.hasError && state.contacts.isEmpty { return .error }
    return .content
}

/** حالة منطقة القائمة داخل المحتوى — مرآة `ContactsListArea` (T3). */
enum ContactsListPhase: Equatable {
    case loading
    case empty
    case noResults
    case loaded
}

func contactsListPhase(for state: ContactsUiState) -> ContactsListPhase {
    if !state.contacts.isEmpty { return .loaded }
    if state.isSearchActive { return .noResults }
    if state.isLoading { return .loading }
    return .empty
}

// MARK: - منتقي الأرقام (مرآة dispatchResolved في ContactsViewModel.kt)

/**
 * بناء الـ Intent بعد اختيار رقم من المنتقي: الرسالة ⇒ `ContactsIntent.Message`،
 * وغيرها (الاتصال) ⇒ `ContactsIntent.CallNumber` — الدلالة نفسها لـ
 * `dispatchResolved` في الـ ViewModel المشترك، بلا منطق محلي جديد.
 */
func contactsNumberPickerIntent(
    contactId: Int64,
    e164: String,
    action: ContactSwipeAction
) -> any ContactsIntent {
    switch action {
    case .message:
        return ContactsIntentMessage(contactId: contactId, e164: e164)
    default:
        return ContactsIntentCallNumber(contactId: contactId, e164: e164)
    }
}

// MARK: - قوالب النصوص (مرآة replace("%d", …) في ContactsStringsProvider.kt)

/**
 * تعويض العدد في قالب الموارد — استبدال حرفي لـ «%d» كي يطابق سلوك
 * `ContactsStringsProvider` في Compose حرفياً (بلا تنسيق موضعي مختلف).
 */
func formatCountTemplate(_ template: String, _ count: Int32) -> String {
    template.replacingOccurrences(of: "%d", with: String(count))
}

// MARK: - ترميز أرقام tel:/sms: (مرآة Uri.encode في AndroidApp.kt — agy — F1)

/**
 * ترميز رقم الهاتف لاستخدام آمن في `tel:`/`sms:` — سلوكياً كمرآة `Uri.encode`
 * في أندرويد مع التكيف مع قواعد Apple (يجب ترميز `*`/`#` كي لا يُفسَّر `#`
 * كفاصل fragment ويُقتطع من الرقم المطلوب):
 * - `+` يبقى حرفياً (الصيغة الدولية القياسية لـ tel:).
 * - الأرقام والأحرف والفواصل الشائعة `-().~` تبقى حرفية.
 * - أي محرف آخر (`*` `#` `,` الفراغ…) يُشفر بالنسبة المئوية، فينجو الرقم
 *   كاملاً عبر `URL(string:)` (التي تُرجع صفراً للفراغ الحرفي) ويستلمه
 *   الهاتف/الرسائل بعد فك الترميز كما هو.
 * نقية وقابلة للاختبار مباشرة (مراجعة F1 — جولة التحقق النهائية).
 */
func e164PercentEncoded(_ e164: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "+-().~")
    return e164.addingPercentEncoding(withAllowedCharacters: allowed) ?? e164
}
