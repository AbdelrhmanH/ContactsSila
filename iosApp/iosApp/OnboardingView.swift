import SwiftUI
import ContactsUI
import SharedLogic

/**
 * شاشات التهيئة الأولى على SwiftUI (Issue #26 — T5 · المرحلة 1 ضيف فقط).
 * تعرض UiState وترسل OnboardingIntent فقط (لا منطق أعمال ولا نص ثابت في الكود —
 * كل النصوص من Localizable.strings عبر ar.lproj / en.lproj). الخطوات:
 * ترحيب ← شرح الصلاحية ← متابعة كضيف ← أهم 5 أشخاص ← جارٍ التنظيم.
 */
struct OnboardingView: View {

    @State private var store = OnboardingViewModelStore()

    var body: some View {
        OnboardingStepView(store: store)
            .environment(\.locale, Locale(identifier: store.state.language.code))
            // مهمتان منفصلتان لا متتاليتان: observeState تنتظر تدفقاً لا نهائياً
            // (StateFlow) — أي انتظار متتابع بعدها يبقى بلا أثر أبداً، فتضيع
            // كل الـ Effects (طلب الصلاحية / فتح الإعدادات) على iOS.
            .task { await store.observeState() }
            .task { await store.observeEffects() }
    }
}

private struct OnboardingStepView: View {

    @Bindable var store: OnboardingViewModelStore

    var body: some View {
        switch store.state.step {
        case .welcome:
            WelcomePagerView(store: store)
        case .permission:
            PermissionExplainerView(store: store)
        case .guestLogin:
            GuestStepView(store: store)
        case .topFive:
            TopFiveView(store: store)
        case .organizing:
            OrganizingView(store: store)
        default:
            WelcomePagerView(store: store)
        }
    }
}

// MARK: - الترحيب (3 شرائح: نظّم · خدمات حية · أرحام + "ابدأ")

private struct WelcomePagerView: View {

    @Bindable var store: OnboardingViewModelStore
    @State private var page = 0

    private var strings: OnboardingLocalizedStrings {
        OnboardingLocalizedStrings(language: store.state.language)
    }

    var body: some View {
        VStack(spacing: 16) {
            TabView(selection: $page) {
                welcomePage(title: strings.welcomeTitleOrganize, body: strings.welcomeBodyOrganize)
                    .tag(0)
                welcomePage(title: strings.welcomeTitleLive, body: strings.welcomeBodyLive)
                    .tag(1)
                welcomePage(title: strings.welcomeTitleArham, body: strings.welcomeBodyArham)
                    .tag(2)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            if page == 2 {
                Button(strings.actionStart) { store.startOnboarding() }
                    .buttonStyle(.borderedProminent)
            } else {
                Button(strings.actionContinue) { page += 1 }
                    .buttonStyle(.bordered)
            }
        }
        .padding(24)
    }

    private func welcomePage(title: String, bodyText: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Text(title)
                .font(.largeTitle.bold())
                .foregroundStyle(.emeraldSila)
                .multilineTextAlignment(.center)
            Text(bodyText)
                .font(.headline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(16)
    }
}

// MARK: - شرح صلاحية جهات الاتصال (مصفوفة الأذونات — Issue #20)

private struct PermissionExplainerView: View {

    @Bindable var store: OnboardingViewModelStore

    private var strings: OnboardingLocalizedStrings {
        OnboardingLocalizedStrings(language: store.state.language)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(strings.permissionTitle)
                    .font(.title.bold())
                    .foregroundStyle(.emeraldSila)
                Text(strings.permissionBody)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                if store.state.contactsPermission == .denied {
                    noteCard(strings.permissionDeniedOnceBody)
                    Button(strings.actionTryAgain) { store.requestContactsPermission() }
                        .buttonStyle(.borderedProminent)
                }

                if store.state.contactsPermission == .deniedForever {
                    noteCard(strings.permissionDeniedForeverBody)
                    Button(strings.permissionOpenSettingsButton) { store.openAppSettingsIntent() }
                        .buttonStyle(.borderedProminent)
                }

                if store.state.contactsPermission != .denied
                    && store.state.contactsPermission != .deniedForever {
                    Button(strings.permissionGrantButton) { store.requestContactsPermission() }
                        .buttonStyle(.borderedProminent)
                }

                Button(strings.permissionSkipButton) { store.skipPermission() }
                    .buttonStyle(.bordered)
            }
            .padding(24)
        }
    }

    private func noteCard(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - متابعة كضيف (المرحلة 1: لا OTP ولا حساب)

private struct GuestStepView: View {

    @Bindable var store: OnboardingViewModelStore

    private var strings: OnboardingLocalizedStrings {
        OnboardingLocalizedStrings(language: store.state.language)
    }

    var body: some View {
        VStack(spacing: 12) {
            Text(strings.guestTitle)
                .font(.title.bold())
                .foregroundStyle(.emeraldSila)
            Text(strings.guestBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text(strings.guestLoginNote)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(strings.guestSkipButton) { store.continueAsGuest() }
                .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }
}

// MARK: - أهم 5 أشخاص (بحث + اختيار حتى خمسة + صلة قرابة سريعة)

private struct TopFiveView: View {

    @Bindable var store: OnboardingViewModelStore
    @State private var localSearchQuery = ""

    private var strings: OnboardingLocalizedStrings {
        OnboardingLocalizedStrings(language: store.state.language)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings.topFiveTitle)
                .font(.headline)
                .foregroundStyle(.emeraldSila)
            Text(strings.topFiveBody)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(strings.topFiveCounter(Int32(store.state.selections.count)))
                .font(.footnote)
                .foregroundStyle(.emeraldSila)

            TextField(strings.topFiveSearchHint, text: $localSearchQuery)
                .onChange(of: localSearchQuery) { _, newValue in
                    store.updateSearchQuery(newValue)
                }
                .textFieldStyle(.roundedBorder)

            if store.state.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            } else if store.state.hasContactsError {
                errorView
            } else if store.state.contactsPermission == .denied
                || store.state.contactsPermission == .deniedForever
                || store.state.contactsPermission == .skipped {
                Spacer()
                Text(strings.topFivePermissionMissingBody)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                Button(strings.permissionOpenSettingsButton) { store.openAppSettingsIntent() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Spacer()
            } else {
                if store.state.contactsPermission == .limited {
                    Text(strings.topFiveLimitedBody)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    // عنصر التوسيع النظامي (معيار القبول — Issue #26): يعرض جهاتٍ
                    // لم يُمنح التطبيق الوصول إليها بعد وعلى الضغط يفتح مُنتقي
                    // النظام لتوسيع الوصول (iOS 18+)؛ بعد أي إضافة تُعاد مزامنة
                    // قائمة المرشحين (ADR-0010) عبر إعادة تحميل جهات الاتصال.
                    if #available(iOS 18.0, *) {
                        ContactAccessButton(queryString: localSearchQuery) { _ in
                            store.retryContactsLoad()
                        }
                    }
                }
                if !store.state.selections.isEmpty {
                    selectedRows
                }
                if store.state.candidates.isEmpty {
                    if localSearchQuery.isEmpty {
                        Spacer()
                        emptyBookView
                        Spacer()
                    } else {
                        Spacer()
                        Text(strings.topFiveNoResultsLabel)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding(.vertical, 24)
                        Spacer()
                    }
                } else {
                    contactList
                }
            }

            HStack(spacing: 8) {
                Button(strings.topFiveSkipButton) { store.skipTopFive() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button(strings.topFiveSaveButton) { store.saveTopFive() }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(store.state.selections.isEmpty)
            }
        }
        .padding(16)
    }

    private var errorView: some View {
        VStack(spacing: 8) {
            Spacer()
            Text(strings.errorTitle)
                .font(.headline)
            Text(strings.topFiveErrorBody)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button(strings.actionTryAgain) { store.retryContactsLoad() }
                .buttonStyle(.bordered)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private var emptyBookView: some View {
        VStack(spacing: 8) {
            Text(strings.topFiveEmptyTitle)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(strings.topFiveEmptyBody)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    private var contactList: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(store.state.candidates, id: \.systemContactId) { candidate in
                    CandidateRow(
                        candidate: candidate,
                        isSelected: store.state.selections.contains {
                            $0.systemContactId == candidate.systemContactId
                        },
                        onToggle: { store.toggleSelection(candidate) }
                    )
                }
            }
        }
    }

    private var selectedRows: some View {
        LazyVStack(alignment: .leading, spacing: 6) {
            ForEach(store.state.selections, id: \.systemContactId) { selection in
                VStack(alignment: .leading, spacing: 4) {
                    Text(selection.displayName)
                        .font(.subheadline.bold())
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(
                                OnboardingViewConstants.quickRelations,
                                id: \.self
                            ) { relation in
                                let isSelected = selection.relation == relation
                                Button(action: { store.setRelation(relation, for: selection) }) {
                                    Text(strings.relationLabel(of: relation))
                                        .font(.caption)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(isSelected ? Color.emeraldSila.opacity(0.15) : Color(.systemGray6))
                                        .foregroundStyle(isSelected ? Color.emeraldSila : Color.primary)
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct CandidateRow: View {

    let candidate: Contact
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 12) {
                Text(isSelected ? "☑" : "⬜")
                    .font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(candidate.name.displayName)
                        .font(.body)
                    if let primaryPhone = candidate.phones?.first?.raw {
                        // أرقام الهواتف تظهر LTR دائماً حتى في تخطيط RTL.
                        Text("\u{200E}\(primaryPhone)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - جارٍ التنظيم (انتقال قصير ثم إتمام onboardingCompleted)

private struct OrganizingView: View {

    @Bindable var store: OnboardingViewModelStore

    private var strings: OnboardingLocalizedStrings {
        OnboardingLocalizedStrings(language: store.state.language)
    }

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            ProgressView()
            Text(strings.organizingTitle)
                .font(.headline)
                .foregroundStyle(.emeraldSila)
            Text(strings.organizingBody)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(24)
        .task {
            try? await Task.sleep(nanoseconds: OnboardingViewConstants.organizingMinDelayNanoseconds)
            store.finishOrganizing()
        }
    }
}

// MARK: - مساعدات

enum OnboardingViewConstants {
    /** مرآة لـ ORGANIZING_MIN_DISPLAY_MS في Compose (1.5 ثانية). */
    static let organizingMinDelayNanoseconds: UInt64 = 1_500_000_000

    /** صلة قرابة سريعة — مرآة لـ QUICK_RELATIONS في Compose. */
    static let quickRelations: [Relation] = [
        .father, .mother, .brother, .sister, .spouse,
        .son, .daughter, .inLaw, .closeFriend, .custom
    ]
}

/** تسمية الصلة القرابة من الموارد — لا نص ثابت في الكود. */
extension OnboardingLocalizedStrings {
    func relationLabel(of relation: Relation) -> String {
        switch relation {
        case .father: return relationFather
        case .mother: return relationMother
        case .brother: return relationBrother
        case .sister: return relationSister
        case .spouse: return relationSpouse
        case .son: return relationSon
        case .daughter: return relationDaughter
        case .inLaw: return relationInLaw
        case .closeFriend: return relationCloseFriend
        default: return relationCustom
        }
    }
}

private extension Color {
    static let emeraldSila = Color(red: 15 / 255, green: 157 / 255, blue: 115 / 255)
}
