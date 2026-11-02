import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * جسر آثار المنصة على iOS (T4 — Issue #25) — نقطة الحقن للاختبارات.
 * الجامع الوحيد لقناة `ContactsEffect` هو [ContactsViewModelStore] حصراً
 * (قناة أحادية المستهلك — نمط `SilaAndroidApp` / `OnboardingViewModelStore`)؛
 * التنفيذ الافتراضي يفتح الهاتف/الرسائل/الإعدادات عبر UIKit بلا أي منطق أعمال.
 */
@MainActor
protocol ContactsEffectRouting: AnyObject {
    func openDialer(e164: String)
    func openSms(e164: String)
    func openAppSettings()
}

@MainActor
final class DefaultContactsEffectRouter: ContactsEffectRouting {

    func openDialer(e164: String) {
        guard !e164.isEmpty,
              let url = URL(string: "tel:\(e164PercentEncoded(e164))") else { return }
        UIApplication.shared.open(url)
    }

    func openSms(e164: String) {
        guard !e164.isEmpty,
              let url = URL(string: "sms:\(e164PercentEncoded(e164))") else { return }
        UIApplication.shared.open(url)
    }

    // (الترميز نفسه دالة نقية مشتركة: e164PercentEncoded في
    // ContactsPresentationLogic.swift — مرآة Uri.encode الأندرويدية، agy — F1.)

    /**
     * الرفض النهائي ← إعدادات التطبيق (`UIApplication.openSettingsURLString`) —
     * الطريق الوحيد للمنح بعد الرفض النهائي ولا إعادة طلب تلقائية أبداً
     * (معيار قبول Issue #25).
     */
    func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/**
 * غلاف حالة شاشة جهات الاتصال المشتركة لـ SwiftUI (T4 — Issue #25) بنمط
 * MainViewModelStore/OnboardingViewModelStore (المرحلة 0).
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية من `viewModel.state.value` لمنع وميض شاشة فارغة.
 * 2. جمع التحديثات عبر `for await` (SKIE AsyncSequence من `StateFlow<ContactsUiState>`)
 *    — بلا جسر Flows بديل ولا طبقة منطق أعمال على iOS.
 * 3. العمل على `@MainActor` بالكامل.
 * 4. إعادة توجيه `ContactsIntent` كما هي — لا فرز ولا تصفية ولا حساب حالة محلياً.
 * 5. جمع آثار المنصة (جامع واحد للقناة): الهاتف/الرسائل/الإعدادات عبر
 *    [ContactsEffectRouting]، والمشاركة والمنتقي حالة عرض عابرة تقدَّم من الواجهة.
 */
@MainActor
@Observable
final class ContactsViewModelStore {

    private let viewModel: ContactsViewModel

    private(set) var state: ContactsUiState

    /** Effect عابر: تعددت الأرقام — الواجهة تعرض المنتقي ثم ترسل CallNumber/Message. */
    private(set) var numberPicker: ContactsEffectShowNumberPicker?

    /** Effect عابر: نص مشاركة الاختيار — الواجهة تقدّم ورقة مشاركة النظام. */
    private(set) var pendingShareText: String?

    let effectRouter: any ContactsEffectRouting

    /**
     * الراوتر اختياري بافتراض صفرّي ويُبنى هنا داخل جسم الـ init المعزول على
     * MainActor: مُولِّدات الوسائط الافتراضية تُجمَّع بلا عزل في Swift 5،
     * و`DefaultContactsEffectRouter()` معزول على MainActor — بناؤه في توقيع
     * الـ init كان يفشل الترجمة على CI (iOS 18.2 SDK / Xcode 16.2).
     * مواقع الاستدعاء القائمة (iOSApp و#Preview) تبقى كما هي بلا وسيطة.
     */
    init(
        viewModel: ContactsViewModel = IosKoinBootstrap.shared.getContactsViewModel(),
        effectRouter: (any ContactsEffectRouting)? = nil
    ) {
        self.viewModel = viewModel
        self.effectRouter = effectRouter ?? DefaultContactsEffectRouter()
        self.state = viewModel.state.value
    }

    /**
     * يجمع تحديثات الحالة تدفقياً من `StateFlow` المشترك عبر SKIE AsyncSequence.
     * يتوقف الجمع تلقائياً عند إلغاء الـ Task المرافق لعرض SwiftUI.
     */
    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    /**
     * يجمع آثار المنصة وينفّذها — جامع واحد للقناة حصراً (الشاشات لا تجمعها).
     */
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case let dialer as ContactsEffectOpenDialer:
                effectRouter.openDialer(e164: dialer.e164)
            case let sms as ContactsEffectOpenSms:
                effectRouter.openSms(e164: sms.e164)
            case let share as ContactsEffectShareContacts:
                pendingShareText = share.text
            case is ContactsEffectOpenAppSettings:
                effectRouter.openAppSettings()
            case let picker as ContactsEffectShowNumberPicker:
                numberPicker = picker
            default:
                break
            }
        }
    }

    /// إرسال أي [ContactsIntent] مباشر إلى الـ ViewModel المشترك.
    func send(_ intent: any ContactsIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط، كما في MainViewModelStore).

    func selectCategory(_ category: SharedLogic.Category?) {
        send(ContactsIntentSelectCategory(category: category))
    }

    func updateSearchQuery(_ query: String) {
        send(ContactsIntentUpdateSearchQuery(query: query))
    }

    func setAccountFilter(_ filter: AccountFilter) {
        send(ContactsIntentSetAccountFilter(filter: filter))
    }

    func toggleFavorite(_ contact: Contact) {
        send(ContactsIntentToggleFavorite(contactId: contact.id, isFavorite: !contact.isFavorite))
    }

    /// زر الاتصال بالصف: الرقم الافتراضي أو المنتقي عبر الـ ViewModel المشترك.
    func call(_ contact: Contact) {
        send(ContactsIntentCall(contactId: contact.id))
    }

    /// ضغطة مطولة أو نقرة في وضع التحديد — مفتاح الثبات `contact.id`.
    func toggleSelect(_ contact: Contact) {
        send(ContactsIntentToggleSelect(contactId: contact.id))
    }

    func clearSelection() {
        send(ContactsIntentClearSelection.shared)
    }

    func bulkCategorize(_ category: SharedLogic.Category) {
        send(ContactsIntentBulkCategorize(category: category))
    }

    func bulkSetFavorite(_ isFavorite: Bool) {
        send(ContactsIntentBulkSetFavorite(isFavorite: isFavorite))
    }

    func shareSelection() {
        send(ContactsIntentShareSelection.shared)
    }

    /// يُرسل بعد تأكيد الحذف في العرض حصراً (عقد T3).
    func bulkDelete() {
        send(ContactsIntentBulkDelete.shared)
    }

    func dismissSuggestion(_ card: SuggestionCard) {
        send(ContactsIntentDismissSuggestion(suggestionId: card.id))
    }

    /// بطاقة الفلترة الذكية تنقلك لتصنيف غير المصنّف (نفس سلوك Compose T3).
    func selectSmartFilterSuggestion() {
        selectCategory(SharedLogic.Category.uncategorized)
    }

    func swipe(_ contact: Contact, _ action: ContactSwipeAction) {
        send(ContactsIntentSwipe(contactId: contact.id, action: action))
    }

    func retry() {
        send(ContactsIntentRetry.shared)
    }

    /// الرفض النهائي للصلاحية ← Intent مشترك؛ الـ ViewModel يبث `ContactsEffectOpenAppSettings`
    /// والجامع الوحيد يفتح الإعدادات عبر [ContactsEffectRouting] — لا مسار مباشر من العرض.
    func openSettings() {
        send(ContactsIntentOpenSettings.shared)
    }

    // منتقي الأرقام — حالة العرض العابرة.

    func pickNumber(_ phone: Phone) {
        guard let picker = numberPicker else { return }
        numberPicker = nil
        send(contactsNumberPickerIntent(contactId: picker.contactId, e164: phone.e164, action: picker.action))
    }

    func cancelNumberPicker() {
        numberPicker = nil
    }

    func clearPendingShare() {
        pendingShareText = nil
    }
}
