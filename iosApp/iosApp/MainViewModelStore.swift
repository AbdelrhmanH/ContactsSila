import Foundation
import Observation
import SharedLogic

/**
 * غلاف حالة العرض المشترك لتطبيقات SwiftUI (Issue #5 & Issue #10).
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية مباشرة من `viewModel.state.value` المشتركة لمنع وميض الشاشة الفارغة.
 * 2. جمع التحديثات عبر `for await` المستند إلى `AsyncSequence` الذي يولده SKIE من `StateFlow<MainUiState>`.
 * 3. العمل بالكامل على `@MainActor` لضمان تحديثات خيط الواجهة الرئيسية فورياً.
 * 4. إعادة توجيه الـ Intents كما هي دون تنفيذ أي منطق أعمال أو حسابات حالة محلياً على iOS.
 * 5. ترك حساب وتحديث عداد الأرحام المتأخرة (`overdueArhamCount`) في عهدة Kotlin المشترك فقط.
 */
@MainActor
@Observable
final class MainViewModelStore {

    private let viewModel: MainViewModel
    private(set) var state: MainUiState

    init(viewModel: MainViewModel = IosKoinBootstrap.shared.getMainViewModel()) {
        self.viewModel = viewModel
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
     * إرسال أي [MainIntent] مباشر إلى الـ ViewModel المشترك.
     */
    func send(_ intent: any MainIntent) {
        viewModel.onIntent(intent: intent)
    }

    /**
     * تبديل التبويب النشط عبر إرسال [MainIntent.SelectTab].
     */
    func selectTab(_ tab: DefaultTab) {
        viewModel.onIntent(intent: MainIntentSelectTab(tab: tab))
    }

    /**
     * تبديل اللغة (عربي / إنجليزي) عبر إرسال [MainIntent.ToggleLanguage].
     */
    func toggleLanguage() {
        viewModel.onIntent(intent: MainIntentToggleLanguage.shared)
    }

    /**
     * تغيير وضع السمة (فاتح / داكن / النظام) عبر إرسال [MainIntent.ChangeThemeMode].
     */
    func changeThemeMode(_ mode: AppThemeMode) {
        viewModel.onIntent(intent: MainIntentChangeThemeMode(mode: mode))
    }
}
