import SwiftUI
import SharedLogic

struct ContentView: View {

    var store: MainViewModelStore

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
        TabView(selection: activeTabBinding) {
            tabItemView(
                title: localized("nav_contacts"),
                subtitle: localized("action_search"),
                icon: "person.2.fill"
            )
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
        .environment(\.layoutDirection, store.state.preferences.language.isRtl ? .rightToLeft : .leftToRight)
        .environment(\.locale, Locale(identifier: store.state.preferences.language.code))
        .preferredColorScheme(colorScheme)
        .task {
            await store.observeState()
        }
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
    ContentView(store: MainViewModelStore())
}
