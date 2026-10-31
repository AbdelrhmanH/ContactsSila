import Foundation
import Contacts
import Observation
import SharedLogic
import UIKit

/**
 * غلاف حالة التهيئة الأولى المشتركة لـ SwiftUI (Issue #26 — T5) بنمط MainViewModelStore.
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية من `viewModel.state.value` لمنع وميض شاشة فارغة.
 * 2. جمع التحديثات عبر `for await` (SKIE AsyncSequence من StateFlow).
 * 3. العمل على @MainActor بالكامل.
 * 4. إعادة توجيه OnboardingIntent كما هي — لا منطق أعمال ولا حساب حالة على iOS.
 * 5. تنفيذ Effect المنصّة الوحيد: طلب صلاحية جهات الاتصال عبر `CNContactStore`
 *    (GRANTED / LIMITED / DENIED → DENIED_FOREVER حسب ADR-0009، بلا إعادة
 *    طلب تلقائياً أبداً) وفتح إعدادات التطبيق عند الرفض النهائي.
 */
@MainActor
@Observable
final class OnboardingViewModelStore {

    private let viewModel: OnboardingViewModel
    private(set) var state: OnboardingUiState

    init(viewModel: OnboardingViewModel = IosKoinBootstrap.shared.getOnboardingViewModel()) {
        self.viewModel = viewModel
        self.state = viewModel.state.value
    }

    /**
     * يجمع تحديثات الحالة تدفقياً من StateFlow المشترك عبر SKIE.
     */
    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    /**
     * يجمع Effect المنصّة (الطلب الفعلي للصلاحية / فتح الإعدادات) وينفّذه على iOS.
     */
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is OnboardingEffectRequestContactsPermission:
                await requestContactsAccess()
            case is OnboardingEffectOpenAppSettings:
                openAppSettings()
            default:
                break
            }
        }
    }

    func send(_ intent: any OnboardingIntent) {
        viewModel.onIntent(intent: intent)
    }

    func startOnboarding() {
        send(OnboardingIntentStartOnboarding.shared)
    }

    func requestContactsPermission() {
        send(OnboardingIntentRequestContactsPermission.shared)
    }

    func openAppSettingsIntent() {
        send(OnboardingIntentOpenAppSettings.shared)
    }

    func skipPermission() {
        send(OnboardingIntentSkipPermission.shared)
    }

    func continueAsGuest() {
        send(OnboardingIntentContinueAsGuest.shared)
    }

    func updateSearchQuery(_ query: String) {
        send(OnboardingIntentUpdateSearchQuery(query: query))
    }

    func retryContactsLoad() {
        send(OnboardingIntentRetryContactsLoad.shared)
    }

    func toggleSelection(_ contact: Contact) {
        send(
            OnboardingIntentToggleSelection(
                systemContactId: contact.systemContactId,
                displayName: contact.name.displayName
            )
        )
    }

    func setRelation(_ relation: Relation, for selection: TopFiveSelection) {
        send(
            OnboardingIntentSetRelation(
                systemContactId: selection.systemContactId,
                relation: relation
            )
        )
    }

    func saveTopFive() {
        send(OnboardingIntentSaveTopFive.shared)
    }

    func skipTopFive() {
        send(OnboardingIntentSkipTopFive.shared)
    }

    func finishOrganizing() {
        send(OnboardingIntentFinishOrganizing.shared)
    }

    /**
     * تنفيذ Effect طلب الصلاحية على iOS (لا يُطلب من الدومين ولا من واجهة النظام مباشرةً):
     * - .authorized → GRANTED · .limited → LIMITED (وصول جزئي لا يُعد رفضاً).
     * - .notDetermined → طلب المنح مرة واحدة؛ رفض الإجابة يجعل الحالة
     *   DENIED_FOREVER (iOS لا يمنح طلباً ثانياً بعد الرفض — لا إعادة طلب تلقائياً أبداً).
     * - .denied/.restricted → DENIED_FOREVER (مساره فتح إعدادات التطبيق).
     */
    private func requestContactsAccess() async {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        // .limited حصري لـ iOS 18+ (هدف النشر 17.0) — لا يصح ذكره في switch مباشرة
        //، فيُعالَج قبلها بفحص توافر صريح.
        if #available(iOS 18.0, *), status == .limited {
            send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.limited))
            return
        }
        switch status {
        case .authorized:
            send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.granted))
        case .notDetermined:
            let store = CNContactStore()
            let granted = await withCheckedContinuation { continuation in
                store.requestAccess(for: .contacts) { granted, _ in
                    continuation.resume(returning: granted)
                }
            }
            // إعادة فحص حالة النظام بعد انتهاء الطلب: على iOS 18+ يؤكد المستخدم
            // «اختيار جهات الاتصال…» فيعود granted=true مع حالة .limited —
            // لا يُصنَّف منحاً كاملاً أبداً (مصفوفة الأذونات — Issue #20).
            if #available(iOS 18.0, *) {
                switch CNContactStore.authorizationStatus(for: .contacts) {
                case .limited:
                    send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.limited))
                    return
                case .authorized:
                    send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.granted))
                    return
                default:
                    break
                }
            }
            send(
                OnboardingIntentPermissionResult(
                    state: granted
                        ? OnboardingPermissionState.granted
                        : OnboardingPermissionState.deniedForever
                )
            )
        case .denied, .restricted:
            send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.deniedForever))
        @unknown default:
            send(OnboardingIntentPermissionResult(state: OnboardingPermissionState.deniedForever))
        }
    }

    /**
     * الرفض النهائي ← فتح إعدادات التطبيق (`UIApplication.openSettingsURLString`)
     * — الطريق الوحيد للمنح بعد الرفض النهائي، ولا إعادة طلبٍ من النظام أبداً.
     */
    private func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/**
 * ملف نصوص التهيئة الأولى لـ SwiftUI — مصدره حصرياً
 * `ar.lproj/Localizable.strings` (الافتراضية) و `en.lproj/Localizable.strings`
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 */
struct OnboardingLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    // ترحيب (3 شرائح)
    var welcomeTitleOrganize: String { string("onboarding_welcome_title_organize") }
    var welcomeBodyOrganize: String { string("onboarding_welcome_body_organize") }
    var welcomeTitleLive: String { string("onboarding_welcome_title_live") }
    var welcomeBodyLive: String { string("onboarding_welcome_body_live") }
    var welcomeTitleArham: String { string("onboarding_welcome_title_arham") }
    var welcomeBodyArham: String { string("onboarding_welcome_body_arham") }
    var actionStart: String { string("onboarding_action_start") }
    var actionContinue: String { string("onboarding_action_continue") }

    // شرح الصلاحية (مصفوفة الأذونات — Issue #20)
    var permissionTitle: String { string("onboarding_permission_title") }
    var permissionBody: String { string("onboarding_permission_body") }
    var permissionGrantButton: String { string("onboarding_permission_grant_button") }
    var permissionSkipButton: String { string("onboarding_permission_skip_button") }
    var permissionDeniedOnceBody: String { string("onboarding_permission_denied_once_body") }
    var permissionDeniedForeverBody: String { string("onboarding_permission_denied_forever_body") }
    var permissionOpenSettingsButton: String { string("onboarding_permission_open_settings_button") }
    var permissionLimitedBody: String { string("onboarding_permission_limited_body") }

    // الضيف (Phase 1 لا OTP)
    var guestTitle: String { string("onboarding_guest_title") }
    var guestBody: String { string("onboarding_guest_body") }
    var guestLoginNote: String { string("onboarding_guest_login_note") }

    // أهم 5 أشخاص
    var topFiveTitle: String { string("onboarding_top_five_title") }
    var topFiveBody: String { string("onboarding_top_five_body") }
    var topFiveSearchHint: String { string("onboarding_top_five_search_hint") }
    var topFiveEmptyTitle: String { string("onboarding_top_five_empty_title") }
    var topFiveEmptyBody: String { string("onboarding_top_five_empty_body") }
    var topFivePermissionMissingBody: String { string("onboarding_top_five_permission_missing_body") }
    var topFiveNoResultsLabel: String { string("onboarding_top_five_no_results_label") }
    var topFiveErrorBody: String { string("onboarding_top_five_error_body") }
    var errorTitle: String { string("onboarding_error_title") }
    var actionTryAgain: String { string("onboarding_action_try_again") }
    var topFiveSaveButton: String { string("onboarding_top_five_save_button") }
    var topFiveSkipButton: String { string("onboarding_top_five_skip_button") }

    // صلة القرابة السريعة
    var relationFather: String { string("relation_father") }
    var relationMother: String { string("relation_mother") }
    var relationBrother: String { string("relation_brother") }
    var relationSister: String { string("relation_sister") }
    var relationSpouse: String { string("relation_spouse") }
    var relationSon: String { string("relation_son") }
    var relationDaughter: String { string("relation_daughter") }
    var relationInLaw: String { string("relation_in_law") }
    var relationCloseFriend: String { string("relation_close_friend") }
    var relationCustom: String { string("relation_custom") }

    // جارٍ التنظيم
    var organizingTitle: String { string("onboarding_organizing_title") }
    var organizingBody: String { string("onboarding_organizing_body") }

    /** صيغة عدّاد المختارين من قالب الموارد (نفس علامة %1$d في Compose). */
    func topFiveCounter(_ selected: Int32) -> String {
        String(format: string("onboarding_top_five_counter_template"), selected)
    }
}
