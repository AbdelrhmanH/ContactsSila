import SwiftUI
import SharedLogic

/**
 * مسارات مكدس تبويب جهات الاتصال (T6 — Issue #27 · J-2): iOS يستعمل
 * `NavigationStack(path:)` الأصلي — القائمة (T4) ← التفاصيل ← التعديل،
 * والـ ViewModels المشتركة تُنشأ لكل شاشة عبر معايرات Koin.
 */
enum ContactsRoute: Hashable {
    case details(Int64)
    case edit(Int64?)
}

struct ContentView: View {

    var store: MainViewModelStore

    /// مخزن شاشة جهات الاتصال المشترك (T4 — Issue #25) — نمط MainViewModelStore نفسه.
    var contactsStore: ContactsViewModelStore

    /// مكدس التبويب — حالة عرض أصلية (SwiftUI)؛ التنقل يبث من آثار الـ VM حصراً.
    @State private var contactsPath: [ContactsRoute] = []

    private var activeTabBinding: Binding<DefaultTab> {
        Binding(
            get: { store.state.activeTab },
            set: { store.selectTab($0) }
        )
    }

    private var colorScheme: ColorScheme? {
        switch store.state.preferences.themeMode {
        case .dark:
            return .dark
        case .light:
            return .light
        case .system:
            return nil
        default:
            return nil
        }
    }

    var body: some View {
        Group {
            if !store.state.preferences.onboardingCompleted {
                // بوابة التهيئة الأولى (Issue #26 — T5): حتى اكتمال onboardingCompleted
                // من الحالة المشتركة لا تظهر التبويبات إطلاقاً، ولا تعود التهيئة بعدها.
                OnboardingView()
            } else {
                tabsContent
            }
        }
        .environment(\.layoutDirection, store.state.preferences.language.isRtl ? .rightToLeft : .leftToRight)
        .environment(\.locale, Locale(identifier: store.state.preferences.language.code))
        .preferredColorScheme(colorScheme)
        // ورقة الإضافة الذكية (FAB) — الحالة ملكية MainViewModel المشترك (نفس أندرويد):
        // الإغلاق يُرسل DismissSmartAddSheet ولا تُخزَّن محلياً.
        .confirmationDialog(
            localized("smart_add_title"),
            isPresented: Binding(
                get: { store.state.isSmartAddSheetVisible },
                set: { if !$0 { store.send(MainIntentDismissSmartAddSheet.shared) } }
            ),
            titleVisibility: .visible
        ) {
            // «جهة اتصال جديدة» تفتح نموذج الإضافة (T6 — Issue #27) — نفس أندرويد:
            // إغلاق الورقة ثم الدفع إلى ContactEditRoute بلا معرف (نموذج إنشاء).
            Button(localized("smart_add_new_contact")) {
                store.send(MainIntentDismissSmartAddSheet.shared)
                contactsPath.append(.edit(nil))
            }
            Button(localized("smart_add_family")) {
                store.selectTab(DefaultTab.arham)
                store.send(MainIntentDismissSmartAddSheet.shared)
            }
            Button(localized("smart_add_service")) {
                store.selectTab(DefaultTab.services)
                store.send(MainIntentDismissSmartAddSheet.shared)
            }
            Button(localized("smart_add_qr")) {}
            Button(localized("action_cancel"), role: .cancel) {}
        }
        .task {
            await store.observeState()
        }
    }

    private var tabsContent: some View {
        TabView(selection: activeTabBinding) {
            contactsTabContent
                .tabItem {
                    Label(localized("nav_contacts"), systemImage: "person.2.fill")
                }
                .tag(DefaultTab.contacts)

            tabItemView(
                title: localized("nav_services"),
                subtitle: localized("services_subtitle"),
                icon: "wrench.and.screwdriver.fill"
            )
            .tabItem {
                Label(localized("nav_services"), systemImage: "wrench.and.screwdriver.fill")
            }
            .tag(DefaultTab.services)

            tabItemView(
                title: localized("nav_arham"),
                subtitle: localized("arham_status_ok"),
                icon: "heart.fill"
            )
            .tabItem {
                Label(localized("nav_arham"), systemImage: "heart.fill")
            }
            .badge(store.state.overdueArhamCount > 0 ? Int(store.state.overdueArhamCount) : 0)
            .tag(DefaultTab.arham)
        }
    }

    /**
     * تبويب جهات الاتصال (T4 — Issue #25): شاشة SwiftUI تراقب الحالة المشتركة عبر
     * [ContactsViewModelStore] مع الـ FAB الذكي في الغلاف (نفس أندرويد: الـ FAB
     * ملك الغلاف ولا يظهر إلا في تبويب جهات الاتصال، وفتحه Intent مشترك).
     *
     * T6 — Issue #27: مكدس `NavigationStack(path:)` للقائمة ← التفاصيل ← التعديل؛
     * الكروم الذكي (الـ FAB) مخفي داخل شاشات المكدس (نفس showChrome الأندرويدية)،
     * وViewModels التفاصيل/التعديل تُنشأ لكل شاشة عبر Koin parametersOf.
     */
    private var contactsTabContent: some View {
        NavigationStack(path: $contactsPath) {
            ContactsView(
                store: contactsStore,
                language: store.state.preferences.language,
                onOpenContact: { contactId in
                    contactsPath.append(.details(contactId))
                }
            )
            .navigationDestination(for: ContactsRoute.self) { route in
                switch route {
                case .details(let contactId):
                    ContactDetailsScreen(
                        contactId: contactId,
                        language: store.state.preferences.language,
                        onBack: { popContactsPath() },
                        onEdit: { editId in contactsPath.append(.edit(editId)) }
                    )
                case .edit(let contactId):
                    ContactEditScreen(
                        contactId: contactId,
                        language: store.state.preferences.language,
                        onBack: { popContactsPath() }
                    )
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            // الـ FAB يبقى ظاهراً والورقة مفتوحة فوقه — نفس أندرويد
            // (floatingActionButton ثابت في SilaMainShell)؛ ويختفي داخل
            // شاشات المكدس (التفاصيل/التعديل — نفس showChrome).
            if contactsPath.isEmpty {
                smartAddFab
            }
        }
    }

    /// إرجاع المكدس من معايرات الآثار (NavigateBack من الـ VM المشترك).
    private func popContactsPath() {
        if !contactsPath.isEmpty {
            contactsPath.removeLast()
        }
    }

    private var smartAddFab: some View {
        Button {
            store.send(MainIntentToggleSmartAddSheet.shared)
        } label: {
            Text("＋")
                .font(.title2.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Color.emeraldSila, in: Circle()) // العلامة (#0F9D73 — امتداد T5)
                .shadow(radius: 4, y: 2)
        }
        .accessibilityIdentifier("contacts_fab")
        .accessibilityLabel(localized("smart_add_title"))
        .padding(.trailing, 16)
        .padding(.bottom, 16)
    }

    private func tabItemView(title: String, subtitle: String, icon: String) -> some View {
        VStack(spacing: 16) {
            HStack {
                Spacer()
                Button(action: {
                    store.toggleLanguage()
                }) {
                    Label(localized("action_toggle_language"), systemImage: "globe")
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color(red: 15/255, green: 157/255, blue: 115/255).opacity(0.12))
                        .foregroundColor(Color(red: 15/255, green: 157/255, blue: 115/255))
                        .cornerRadius(16)
                }
                .padding([.top, .trailing], 16)
            }

            Spacer()

            Image(systemName: icon)
                .font(.system(size: 48))
                .foregroundColor(Color(red: 15/255, green: 157/255, blue: 115/255)) // Sila Emerald
            Text(title)
                .font(.title2)
                .fontWeight(.bold)
            Text(subtitle)
                .font(.subheadline)
                .foregroundColor(.secondary)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 247/255, green: 245/255, blue: 242/255)) // Sila Cream Background
    }

    private func localized(_ key: String) -> String {
        let code = store.state.preferences.language.code
        if let path = Bundle.main.path(forResource: code, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return bundle.localizedString(forKey: key, value: nil, table: nil)
        }
        return Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    }
}

#Preview {
    ContentView(
        store: MainViewModelStore(),
        contactsStore: ContactsViewModelStore()
    )
}
