import Foundation
import Observation
import SharedLogic

/**
 * غلاف حالة شاشة مراجعة الفلترة الذكية المشتركة لـ SwiftUI (T7 — Issue #28) بنمط
 * DuplicatesReviewViewModelStore: تهيئة من القيمة الحالية، جمع `for await`، عمل كامل
 * على `@MainActor`، وإعادة توجيه [SmartFilterReviewIntent] كما هي. جامع آثار القناة
 * الوحيد هنا: الرجوع عبر معايرات النافذة والخطأ نصٌّ مترجَم عابر.
 */
@MainActor
@Observable
final class SmartFilterReviewViewModelStore {

    private let viewModel: SmartFilterReviewViewModel

    private(set) var state: SmartFilterReviewUiState

    /** Effect عابر: خطأ مُترجم — المفتاح يُحلّ نصّه في الواجهة من الموارد. */
    private(set) var pendingErrorKey: SmartFilterReviewErrorKey?

    var onNavigateBack: (() -> Void)?

    init() {
        let vm: SmartFilterReviewViewModel = IosKoinBootstrap.shared.getSmartFilterReviewViewModel()
        self.viewModel = vm
        self.state = vm.state.value
    }

    /// للاختبارات حصراً — حقن VM مباشر.
    init(viewModel: SmartFilterReviewViewModel) {
        self.viewModel = viewModel
        self.state = viewModel.state.value
    }

    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    /// جامع واحد للقناة حصراً (الشاشات لا تجمعها).
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is SmartFilterReviewEffectNavigateBack:
                onNavigateBack?()
            case let error as SmartFilterReviewEffectShowError:
                pendingErrorKey = error.messageKey
            default:
                break
            }
        }
    }

    func send(_ intent: any SmartFilterReviewIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط).

    func back() {
        send(SmartFilterReviewIntentBack.shared)
    }

    func toggleItem(contactId: Int64) {
        send(SmartFilterReviewIntentToggleItem(contactId: contactId))
    }

    func toggleGroupAll(category: SharedLogic.Category) {
        send(SmartFilterReviewIntentToggleGroupAll(category: category))
    }

    func apply() {
        send(SmartFilterReviewIntentApply.shared)
    }

    func notThisCategory(contactId: Int64) {
        send(SmartFilterReviewIntentNotThisCategory(contactId: contactId))
    }

    func neverAsk(contactId: Int64) {
        send(SmartFilterReviewIntentNeverAsk(contactId: contactId))
    }

    func clearError() {
        pendingErrorKey = nil
    }

    /// مغادرة نهائية للشاشة — يُحرر الـ VM.
    func clearViewModel() {
        viewModel.clear()
    }
}
