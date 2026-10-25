import SwiftUI
import SharedLogic

struct ContentView: View {
    @State private var isArabic = true
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            tabItemView(
                title: NSLocalizedString("nav_contacts", comment: ""),
                subtitle: NSLocalizedString("action_search", comment: ""),
                icon: "person.2.fill"
            )
            .tabItem {
                Label(NSLocalizedString("nav_contacts", comment: ""), systemImage: "person.2.fill")
            }
            .tag(0)

            tabItemView(
                title: NSLocalizedString("nav_services", comment: ""),
                subtitle: "دليل الخدمات الحي",
                icon: "wrench.and.screwdriver.fill"
            )
            .tabItem {
                Label(NSLocalizedString("nav_services", comment: ""), systemImage: "wrench.and.screwdriver.fill")
            }
            .tag(1)

            tabItemView(
                title: NSLocalizedString("nav_arham", comment: ""),
                subtitle: NSLocalizedString("arham_status_ok", comment: ""),
                icon: "heart.fill"
            )
            .tabItem {
                Label(NSLocalizedString("nav_arham", comment: ""), systemImage: "heart.fill")
            }
            .tag(2)
        }
        .environment(\.layoutDirection, isArabic ? .rightToLeft : .leftToRight)
    }

    private func tabItemView(title: String, subtitle: String, icon: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 48))
                .foregroundColor(Color(red: 15/255, green: 157/255, blue: 115/255)) // Sila Emerald
            Text(title)
                .font(.title2)
                .fontWeight(.bold)
            Text(subtitle)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 247/255, green: 245/255, blue: 242/255)) // Sila Cream Background
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
