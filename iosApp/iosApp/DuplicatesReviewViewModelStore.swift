import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * غلاف حالة شاشة مراجعة المكررات المشتركة لـ SwiftUI (T7 — Issue #28) بنمط
 * ContactDetailsViewModelStore (T6): تهيئة من القيمة الحالية، جمع `for await`، عمل
 * كامل على `@MainActor`، وإعادة توجيه [DuplicatesReviewIntent] كما هي — لا حساب حالة
 * محلياً. جامع آثار القناة الوحيد هنا: الرجوع عبر معايرات النافذة، الاتصال بعضوٍ عبر
 * [ContactDetailsEffectRouting]، والخطأ نصٌّ مترجَم تعرضه الواجهة عابراً.
 *
 * مغادرة الشاشة (`clearViewModel`) تُنهي جلسة التراجع — اللقطة تنقضي زمنياً ويُتبعها
 * المسح المؤجل (عقد ADR-0013) — هذا سلوك الجلسة الموثق لا تنظيفاً عرضياً.
 */
@MainActor
@Observable
final class DuplicatesReviewViewModelStore {

    private let viewModel: DuplicatesReviewViewModel

    private(set) var state: DuplicatesReviewUiState

    /** Effect عابر: خطأ مُترجم — المفتاح يُحلّ نصّه في الواجهة من الموارد (لا نص في الكود). */
    private(set) var pendingErrorKey: DuplicateReviewErrorKey?

    let effectRouter: any ContactDetailsEffectRouting

    var onNavigateBack: (() -> Void)?
    var onOpenMember: ((Int64) -> Void)?

    init(effectRouter: (any ContactDetailsEffectRouting)? = nil) {
        let vm: DuplicatesReviewViewModel = IosKoinBootstrap.shared.getDuplicatesReviewViewModel()
        self.viewModel = vm
        self.effectRouter = effectRouter ?? DefaultContactDetailsEffectRouter()
        self.state = vm.state.value
    }

    /// للاختبارات حصراً — حقن VM مباشر (نمط ContactDetailsViewModelStore).
    init(
        viewModel: DuplicatesReviewViewModel,
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

    /// جامع واحد للقناة حصراً (الشاشات لا تجمعها).
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is DuplicatesReviewEffectNavigateBack:
                onNavigateBack?()
            case let error as DuplicatesReviewEffectShowError:
                pendingErrorKey = error.messageKey
            case let completed as DuplicatesReviewEffectMergeCompleted:
                // إتمام الدمج يُعرض داخل الشاشة (شريط التراجع) — لا قرار تنقل هنا.
                _ = completed.summary.mergedCount
            case is DuplicatesReviewEffectUndoCompleted,
                 is DuplicatesReviewEffectUndoFailed:
                // الحالة يديرها الـ VM (إخفاء الشريط/بقاؤه).
                break
            default:
                break
            }
        }
    }

    func send(_ intent: any DuplicatesReviewIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط).

    func back() {
        send(DuplicatesReviewIntentBack.shared)
    }

    func toggleExpanded(groupId: String) {
        send(DuplicatesReviewIntentToggleExpanded(groupId: groupId))
    }

    func mergeGroup(groupId: String) {
        send(DuplicatesReviewIntentMergeGroup(groupId: groupId))
    }

    func mergeAllConfirmed() {
        send(DuplicatesReviewIntentMergeAllConfirmed.shared)
    }

    func markNotDuplicates(groupId: String) {
        send(DuplicatesReviewIntentMarkNotDuplicates(groupId: groupId))
    }

    func undoMerge() {
        send(DuplicatesReviewIntentUndoMerge.shared)
    }

    func dismissUndoBar() {
        send(DuplicatesReviewIntentDismissUndoBar.shared)
    }

    /// الاتصال بجهة عضو — قرار منصوي عبر جهاز الآثار نفسه (نمط التفاصيل).
    func callMember(_ e164: String) {
        effectRouter.openDialer(e164: e164)
    }

    func openMember(_ contactId: Int64) {
        onOpenMember?(contactId)
    }

    func clearError() {
        pendingErrorKey = nil
    }

    /// مغادرة نهائية للشاشة — يُحرر الـ VM (جلسة التراجع تنتهي بالمغادرة — ADR-0013).
    func clearViewModel() {
        viewModel.clear()
    }
}
