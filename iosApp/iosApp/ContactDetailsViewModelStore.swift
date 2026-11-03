import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * جسر آثار المنصة لشاشة التفاصيل على iOS (T6 — Issue #27) — نقطة الحقن للاختبارات.
 * الجامع الوحيد لقناة `ContactDetailsEffect` هو [ContactDetailsViewModelStore] حصراً
 * (قناة أحادية المستهلك — نمط `ContactsViewModelStore` من T4)؛ التنفيذ الافتراضي
 * يفتح الهاتف/الرسائل/البريد/الروابط/التطبيقات المتصلة عبر UIKit بلا أي منطق أعمال.
 */
@MainActor
protocol ContactDetailsEffectRouting: AnyObject {
    func openDialer(e164: String)
    func openSms(e164: String)
    func openEmail(address: String)
    func openUrl(url: String)
    func openConnectedApp(scheme: String, e164: String)
}

@MainActor
final class DefaultContactDetailsEffectRouter: ContactDetailsEffectRouting {
    func openDialer(e164: String) {
        guard !e164.isEmpty, let url = URL(string: "tel:\(e164PercentEncoded(e164))") else { return }
        UIApplication.shared.open(url)
    }

    func openSms(e164: String) {
        guard !e164.isEmpty, let url = URL(string: "sms:\(e164PercentEncoded(e164))") else { return }
        UIApplication.shared.open(url)
    }

    func openEmail(address: String) {
        guard !address.isEmpty, let url = URL(string: "mailto:\(addressUrlEncoded(address))") else { return }
        UIApplication.shared.open(url)
    }

    /** الرابط قد يكون رديئاً (مستخدم أدخله يدوياً) — الفشل هنا بلا انهيار (نفس أندرويد). */
    func openUrl(url: String) {
        guard let target = URL(string: url) else { return }
        UIApplication.shared.open(target)
    }

    /**
     * فتح تطبيق متصل مراسلة برقم الجهة (T6): واتساب عبر `wa.me` وتيليجرام
     * `tg://resolve` وسيغنال بمخططه — والفشل (اختفى التطبيق بين الكشف والفتح)
     * بلا انهيار (مرآة openConnectedApp الأندرويدية بلا launch-intent بديل على iOS).
     */
    func openConnectedApp(scheme: String, e164: String) {
        let digits = e164.filter { $0.isNumber }
        let urlString: String
        switch scheme {
        case "whatsapp": urlString = "https://wa.me/\(digits)"
        case "tg": urlString = "tg://resolve?phone=\(digits)"
        case "sgnl": urlString = "sgnl://"
        default: urlString = "\(scheme)://"
        }
        guard let url = URL(string: urlString) else { return }
        UIApplication.shared.open(url)
    }
}

/** ترميز عنوان البريد في صيغة `mailto:` — مرآة e164PercentEncoded (نقية وقابلة للفحص). */
func addressUrlEncoded(_ address: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "@.-_+")
    return address.addingPercentEncoding(withAllowedCharacters: allowed) ?? address
}

/**
 * غلاف حالة شاشة تفاصيل جهة الاتصال المشتركة لـ SwiftUI (T6 — Issue #27) بنمط
 * ContactsViewModelStore (T4 — Issue #25).
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية من `viewModel.state.value` لمنع وميض شاشة فارغة.
 * 2. جمع التحديثات عبر `for await` (SKIE AsyncSequence من `StateFlow<ContactDetailsUiState>`).
 * 3. العمل على `@MainActor` بالكامل.
 * 4. إعادة توجيه `ContactDetailsIntent` كما هي — لا فرز ولا حساب حالة محلياً.
 * 5. جمع آثار المنصة (جامع واحد للقناة): التنقل عبر معايرات النافذة
 *    ([onNavigateBack]/[onNavigateToEdit]) والمنصة عبر [ContactDetailsEffectRouting]،
 *    والمشاركة/الخطأ حالة عرض عابرة تقدَّم من الواجهة.
 */
@MainActor
@Observable
final class ContactDetailsViewModelStore {

    private let viewModel: ContactDetailsViewModel

    private(set) var state: ContactDetailsUiState

    /** Effect عابر: مشاركة vCard — الواجهة تقدّم ورقة مشاركة النظام. */
    private(set) var pendingShareText: String?

    /** Effect عابر: فشل الحذف — الواجهة تعرض تنبيهاً بنصها المترجم (لا نص في الكود). */
    private(set) var showDeleteError = false

    let effectRouter: any ContactDetailsEffectRouting

    /** معايرات النافذة — يضبطها شريط التنقل (ContentView) لتحريك مكدس NavigationStack. */
    var onNavigateBack: (() -> Void)?
    var onNavigateToEdit: ((Int64) -> Void)?

    /** علم داخلي: آخر خروج كان دفع شاشة التعديل (لا يُحسب خروجاً نهائياً للـ VM). */
    private(set) var isPushingEdit = false

    /**
     * الراوتر اختياري بافتراض صفرّي ويُبنى داخل جسم الـ init (نمط T4 المجرَّب —
     * بناؤه في التوقيع كان يفشل الترجمة على CI).
     */
    init(
        contactId: Int64,
        effectRouter: (any ContactDetailsEffectRouting)? = nil
    ) {
        let vm: ContactDetailsViewModel = IosKoinBootstrap.shared.getContactDetailsViewModel(contactId: contactId)
        self.viewModel = vm
        self.effectRouter = effectRouter ?? DefaultContactDetailsEffectRouter()
        self.state = vm.state.value
    }

    /// للاختبارات حصراً — حقن VM مباشر (نمط ContactsViewModelStore).
    init(
        viewModel: ContactDetailsViewModel,
        effectRouter: (any ContactDetailsEffectRouting)? = nil
    ) {
        self.viewModel = viewModel
        self.effectRouter = effectRouter ?? DefaultContactDetailsEffectRouter()
        self.state = viewModel.state.value
    }

    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    /// جامع واحد للقناة حصراً (الشاشات لا تجمعها) — التنقل عبر معايرات النافذة.
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case let dialer as ContactDetailsEffectOpenDialer:
                effectRouter.openDialer(e164: dialer.e164)
            case let sms as ContactDetailsEffectOpenSms:
                effectRouter.openSms(e164: sms.e164)
            case let mail as ContactDetailsEffectOpenEmail:
                effectRouter.openEmail(address: mail.address)
            case let web as ContactDetailsEffectOpenUrl:
                effectRouter.openUrl(url: web.url)
            case let app as ContactDetailsEffectOpenExternalApp:
                effectRouter.openConnectedApp(scheme: app.scheme, e164: app.e164)
            case let edit as ContactDetailsEffectNavigateToEdit:
                isPushingEdit = true
                onNavigateToEdit?(edit.contactId)
            case is ContactDetailsEffectNavigateBack:
                onNavigateBack?()
            case let share as ContactDetailsEffectShareVCard:
                pendingShareText = share.vcardText
            case is ContactDetailsEffectShowDeleteError:
                showDeleteError = true
            default:
                break
            }
        }
    }

    /// إرسال أي [ContactDetailsIntent] مباشر إلى الـ ViewModel المشترك.
    func send(_ intent: any ContactDetailsIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط).

    func toggleFavorite(_ contact: Contact) {
        send(ContactDetailsIntentToggleFavorite(contactId: contact.id, isFavorite: !contact.isFavorite))
    }

    func changeCategory(_ contactId: Int64, _ category: SharedLogic.Category) {
        send(ContactDetailsIntentChangeCategory(contactId: contactId, category: category))
    }

    /// الاتصال بالرقم الافتراضي (الأساسي ثم الوحيد) — الفعل يُحل في الـ VM حصراً.
    func call(_ contact: Contact) {
        send(ContactDetailsIntentCall(contactId: contact.id))
    }

    func callNumber(_ e164: String) {
        send(ContactDetailsIntentCallNumber(e164: e164))
    }

    func message(_ e164: String) {
        send(ContactDetailsIntentMessage(e164: e164))
    }

    func email(_ address: String) {
        send(ContactDetailsIntentEmail(address: address))
    }

    func openWebsite(_ url: String) {
        send(ContactDetailsIntentOpenWebsite(url: url))
    }

    func openConnectedApp(_ app: ConnectedApp, e164: String) {
        send(ContactDetailsIntentOpenConnectedApp(app: app, e164: e164))
    }

    func edit(_ contactId: Int64) {
        send(ContactDetailsIntentEdit(contactId: contactId))
    }

    func shareVCard(_ contactId: Int64) {
        send(ContactDetailsIntentShareVCard(contactId: contactId))
    }

    /// يبدأ نافذة التراجع 5 ثوانٍ (J-5) — بلا كتابة في أي طرف حتى انصرافها.
    func requestDelete() {
        send(ContactDetailsIntentRequestDelete.shared)
    }

    /// تراجع خلال النافذة — لا يكتب شيئاً في أي طرف (J-5).
    func undoDelete() {
        send(ContactDetailsIntentUndoDelete.shared)
    }

    /// زر الرجوع — الفعل NavigateBack يعود عبر [onNavigateBack].
    func back() {
        send(ContactDetailsIntentBack.shared)
    }

    func clearPendingShare() {
        pendingShareText = nil
    }

    func clearDeleteError() {
        showDeleteError = false
    }

    /** مغادرة نهائية للشاشة (بعد pop) — يحرر الـ VM (يلغي نافذة التراجع — J-5). */
    func clearViewModel() {
        isPushingEdit = false
        viewModel.clear()
    }

    /// خروج الشاشة — يُستدعى من onDisappear: دفع تعديل = لا تحرير (يعود للشاشة).
    func handleDisappear() {
        if isPushingEdit {
            isPushingEdit = false
        } else {
            viewModel.clear()
        }
    }
}
