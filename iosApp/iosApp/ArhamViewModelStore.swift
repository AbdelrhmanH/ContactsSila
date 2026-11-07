import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * جسر آثار أرحام المنصوية على iOS (T8-14 — §L.1) — نقطة الحقن للاختبارات.
 * الجامع الوحيد لقناة `ArhamEffect` هو [ArhamViewModelStore] حصراً (قناة
 * أحادية المستهلك — نمط `ContactsViewModelStore`)؛ الاتصال يفتح الطلب الهاتفي
 * عبر UIKit، وطلب دور الفرز (AC-4) أندرويدي حصراً — لا عمل على iOS (موثق).
 */
@MainActor
protocol ArhamEffectRouting: AnyObject {
    func openDialer(phone: String)
    func requestScreeningRole()
}

@MainActor
final class DefaultArhamEffectRouter: ArhamEffectRouting {

    func openDialer(phone: String) {
        guard !phone.isEmpty,
              let url = URL(string: "tel:\(e164PercentEncoded(phone))") else { return }
        // زر الاتصال يُعرض فقط حين canOpenURL("tel:") صادق (§L.4) — هنا الاتصال
        // ذاته؛ الفشل الصامت للمسار محفوظ السلوك (لا حالة خطأ محلية).
        UIApplication.shared.open(url)
    }

    func requestScreeningRole() {
        // AC-4: دور ROLE_CALL_SCREENING آندرويدي حصراً — مصدر الموافقة المشترك
        // يعيد null على iOS فالحالة HIDDEN والبطاقة مخفية ولا يُبث هذا الأثر
        // أصلاً؛ لا حوار وهمي بلا دور حقيقي (حدّ المنصة — لا ادعاء).
    }
}

/**
 * غلاف حالة شاشة أرحام المشتركة لـ SwiftUI (T8-14 — §L.1) بنمط
 * ContactsViewModelStore/MainViewModelStore.
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية من `viewModel.state.value` لمنع وميض شاشة فارغة.
 * 2. جمع التحديثات عبر `for await` (SKIE AsyncSequence من `StateFlow<ArhamUiState>`).
 * 3. العمل على `@MainActor` بالكامل.
 * 4. إعادة توجيه `ArhamIntent` كما هي — لا فرز ولا حساب حالة محلياً على iOS
 *    (لا منطق أعمال في الواجهة — AGENTS.md).
 * 5. جمع الآثار المنصوية (جامع واحد للقناة): الاتصال عبر [ArhamEffectRouting]،
 *    والاحتفال حالة عرض عابرة (ثانيتان — مرآة delay(2_000) في Compose).
 */
@MainActor
@Observable
final class ArhamViewModelStore {

    private let viewModel: ArhamViewModel

    private(set) var state: ArhamUiState

    /** Effect عابر: الاحتفال (AC-12) — يُخفى بعد ثانيتين تلقائياً. */
    private(set) var isCelebrating: Bool

    /** تحصين التزامن: احتفال أحدث يلغي إخفاء احتفالٍ سابق (بلا سباق بين الأثرين). */
    private var celebrationGeneration = 0

    let effectRouter: any ArhamEffectRouting

    /**
     * الراوتر اختياري بافتراض صفرّي ويُبنى داخل جسم الـ init المعزول على MainActor —
     * نمط ContactsViewModelStore المجرَّب على CI (بناؤه في توقيع الـ init يفشل الترجمة).
     */
    init(
        viewModel: ArhamViewModel = IosKoinBootstrap.shared.getArhamViewModel(),
        effectRouter: (any ArhamEffectRouting)? = nil
    ) {
        self.viewModel = viewModel
        self.effectRouter = effectRouter ?? DefaultArhamEffectRouter()
        self.state = viewModel.state.value
        self.isCelebrating = false
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
            case let dial as ArhamEffectDial:
                effectRouter.openDialer(phone: dial.phone)
            case is ArhamEffectShowCelebrationConfetti:
                showCelebration()
            case is ArhamEffectRequestScreeningRole:
                effectRouter.requestScreeningRole()
            default:
                break
            }
        }
    }

    /// مرآة `CelebrationOverlay` العابرة في Compose: تظهر ثم تختفي بعد ثانيتين،
    /// وعدّاد الأجيال يمنع مؤقّتٍ قديماً من إخفاء احتفال أحدث.
    private func showCelebration() {
        celebrationGeneration += 1
        let generation = celebrationGeneration
        isCelebrating = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let self, self.celebrationGeneration == generation else { return }
            self.isCelebrating = false
        }
    }

    /// إرسال أي [ArhamIntent] مباشر إلى الـ ViewModel المشترك.
    func send(_ intent: any ArhamIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط، كما في ContactsViewModelStore).

    func changePeriod(_ period: ArhamPeriod) {
        send(ArhamIntentChangePeriod(period: period))
    }

    /// زر الاتصال بالصف: يسجّل محاولة IN_APP عبر الآلة الحالات (AC-3) — من Kotlin حصراً.
    func recordCall(contactId: Int64, phone: String?) {
        // الأرقام غير القابلة للفراغ تجسر أصلية (Int64) والقابلة تُعلَّب مرة واحدة (§L.2).
        send(ArhamIntentRecordCall(contactId: contactId, phone: phone))
    }

    func toggleTree() {
        send(ArhamIntentToggleTree.shared)
    }

    func toggleConfirmations() {
        send(ArhamIntentToggleConfirmations.shared)
    }

    func resolveConfirmation(eventId: String, answer: ConfirmationAnswer) {
        send(ArhamIntentResolveConfirmation(eventId: eventId, answer: answer))
    }

    func openKinshipEditor(
        contactId: Int64,
        preselectedRelation: Relation? = nil,
        restrictToLayer: KinshipLayer? = nil
    ) {
        send(ArhamIntentOpenKinshipEditor(
            contactId: contactId,
            preselectedRelation: preselectedRelation,
            restrictToLayer: restrictToLayer
        ))
    }

    func dismissKinshipEditor() {
        send(ArhamIntentDismissKinshipEditor.shared)
    }

    /// الحفظ نيةٌ حصراً — الحسم في `UpdateKinshipUseCase` المشترك (لا كتابة هنا).
    func saveKinship(
        contactId: Int64,
        relation: Relation,
        frequency: ArhamFrequency?,
        parentContactId: Int64?,
        pauseForDays: Int?
    ) {
        send(ArhamIntentSaveKinship(
            contactId: contactId,
            relation: relation,
            frequency: frequency,
            parentContactId: parentContactId.map { KotlinLong(value: $0) },
            pauseForDays: pauseForDays.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) }
        ))
    }

    func openTreeNodeSheet(contactId: Int64) {
        send(ArhamIntentOpenTreeNodeSheet(contactId: contactId))
    }

    func dismissTreeNodeSheet() {
        send(ArhamIntentDismissTreeNodeSheet.shared)
    }

    func openGenerationAdd(_ layer: KinshipLayer) {
        send(ArhamIntentOpenGenerationAdd(layer: layer))
    }

    func dismissGenerationAddPicker() {
        send(ArhamIntentDismissGenerationAddPicker.shared)
    }

    /// الإيقاف عبر use case حصراً (قراءة Room SSOT — نفس أندرويد).
    func stopEmergency() {
        send(ArhamIntentStopEmergency.shared)
    }

    func requestScreeningConsent() {
        send(ArhamIntentRequestScreeningConsent.shared)
    }

    func refreshScreeningConsent() {
        send(ArhamIntentRefreshScreeningConsent.shared)
    }

    func dismissScreeningConsent() {
        send(ArhamIntentDismissScreeningConsent.shared)
    }
}
