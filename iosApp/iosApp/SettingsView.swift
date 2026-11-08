import SwiftUI
import SharedLogic

/**
 * شاشة الإعدادات والتفضيلات لـ SwiftUI (T9 — Issue #30).
 * تطبق مبادئ Clean Architecture و Offline-First:
 * الواجهة تعرض UiState وترسل Intent فقط (AGENTS.md).
 */
struct SettingsView: View {

    @Bindable var store: SettingsViewModelStore
    let language: AppLanguage
    var onDismiss: (() -> Void)? = nil

    private var strings: SettingsLocalizedStrings {
        SettingsLocalizedStrings(language: language)
    }

    @State private var exportPassword = ""
    @State private var showExportAlert = false
    @State private var newKeyword = ""
    @State private var selectedKeywordCategory: Category = .family

    var body: some View {
        NavigationStack {
            Form {
                appearanceSection
                generalSection
                arhamSection
                organizationSection
                ringtonesSection
                backupSection
                aboutSection
            }
            .navigationTitle(strings.screenTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let onDismiss {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(strings.save) {
                            onDismiss()
                        }
                    }
                }
            }
            .alert(strings.exportBackup, isPresented: ) {
                SecureField(strings.backupPasswordHint, text: )
                Button(strings.exportBackup) {
                    if !exportPassword.isEmpty {
                        store.send(SettingsIntentExportBackup(password: exportPassword))
                        exportPassword = ""
                    }
                }
                Button(strings.cancel, role: .cancel) {
                    exportPassword = ""
                }
            } message: {
                Text(strings.backupDescription)
            }
        }
        .task {
            await store.observeState()
        }
        .task {
            await store.observeEffects()
        }
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section(header: Text(strings.sectionAppearance)) {
            // السمة
            Picker(strings.theme, selection: Binding(
                get: { store.state.preferences.themeMode },
                set: { store.send(SettingsIntentSetThemeMode(mode: $0)) }
            )) {
                Text("فاتح / Light").tag(AppThemeMode.light)
                Text("داكن / Dark").tag(AppThemeMode.dark)
                Text("حسب النظام / System").tag(AppThemeMode.system)
            }

            // اللغة
            Picker(strings.languageTitle, selection: Binding(
                get: { store.state.preferences.language },
                set: { store.send(SettingsIntentSetLanguage(language: $0)) }
            )) {
                Text("العربية").tag(AppLanguage.arabic)
                Text("English").tag(AppLanguage.english)
            }

            // الأرقام
            Picker(strings.digits, selection: Binding(
                get: { store.state.preferences.digits },
                set: { store.send(SettingsIntentSetDigits(digits: $0)) }
            )) {
                Text(strings.digitsArabic).tag(AppDigits.arabic)
                Text(strings.digitsHindi).tag(AppDigits.hindi)
            }

            // ترتيب الأسماء
            Picker(strings.nameOrder, selection: Binding(
                get: { store.state.preferences.nameOrder },
                set: { store.send(SettingsIntentSetNameOrder(order: $0)) }
            )) {
                Text(strings.nameOrderFirstLast).tag(NameOrder.firstLast)
                Text(strings.nameOrderLastFirst).tag(NameOrder.lastFirst)
            }
        }
    }

    private var generalSection: some View {
        Section(header: Text(strings.sectionGeneral)) {
            // التبويب الافتراضي
            Picker(strings.defaultTab, selection: Binding(
                get: { store.state.preferences.defaultTab },
                set: { store.send(SettingsIntentSetDefaultTab(tab: $0)) }
            )) {
                Text("جهات الاتصال / Contacts").tag(DefaultTab.contacts)
                Text("دليل الخدمات / Services").tag(DefaultTab.services)
                Text("أرحام / Arham").tag(DefaultTab.arham)
            }

            // سحب البداية
            Picker(strings.swipeStart, selection: Binding(
                get: { store.state.preferences.swipeStartAction },
                set: { store.send(SettingsIntentSetSwipeActions(start: $0, end: store.state.preferences.swipeEndAction)) }
            )) {
                Text(strings.swipeCall).tag(ContactSwipeAction.call)
                Text(strings.swipeMessage).tag(ContactSwipeAction.message)
                Text(strings.swipeNone).tag(ContactSwipeAction.none)
            }

            // سحب النهاية
            Picker(strings.swipeEnd, selection: Binding(
                get: { store.state.preferences.swipeEndAction },
                set: { store.send(SettingsIntentSetSwipeActions(start: store.state.preferences.swipeStartAction, end: $0)) }
            )) {
                Text(strings.swipeCall).tag(ContactSwipeAction.call)
                Text(strings.swipeMessage).tag(ContactSwipeAction.message)
                Text(strings.swipeNone).tag(ContactSwipeAction.none)
            }
        }
    }

    private var arhamSection: some View {
        Section(header: Text(strings.sectionArham)) {
            Toggle(strings.arhamNotifications, isOn: Binding(
                get: { store.state.preferences.arhamNotificationsEnabled },
                set: { store.send(SettingsIntentSetArhamNotifications(enabled: $0)) }
            ))

            if store.state.preferences.arhamNotificationsEnabled {
                Stepper(
                    "\(strings.arhamMaxPerDay): \(store.state.preferences.arhamMaxPerDay)",
                    value: Binding(
                        get: { Int(store.state.preferences.arhamMaxPerDay) },
                        set: { store.send(SettingsIntentSetArhamMaxPerDay(max: Int32($0))) }
                    ),
                    in: 1...10
                )

                HStack {
                    Text(strings.quietHours)
                    Spacer()
                    Text("\(store.state.preferences.quietHoursStartHour):00 - \(store.state.preferences.quietHoursEndHour):00")
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var organizationSection: some View {
        Section(header: Text(strings.sectionOrganization)) {
            Toggle(strings.smartFilter, isOn: Binding(
                get: { store.state.preferences.smartFilterEnabled },
                set: { store.send(SettingsIntentSetSmartFilter(enabled: $0)) }
            ))

            Toggle(strings.duplicateScan, isOn: Binding(
                get: { store.state.preferences.duplicateScanEnabled },
                set: { store.send(SettingsIntentSetDuplicateScan(enabled: $0)) }
            ))
        }
    }

    private var ringtonesSection: some View {
        Section(header: Text(strings.sectionRingtones)) {
            Text(strings.ringtoneIosNotice)
                .font(.footnote)
                .foregroundColor(.secondary)

            Button {
                store.send(SettingsIntentShowRingtoneGuidance.shared)
                store.send(SettingsIntentDismissRingtoneGuidance.shared)
                if let url = URL(string: "contacts://"), UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url)
                } else if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label(strings.openContactsApp, systemImage: "arrow.up.forward.app")
            }
        }
    }

    private var backupSection: some View {
        Section(header: Text(strings.sectionBackup)) {
            Text(strings.backupDescription)
                .font(.footnote)
                .foregroundColor(.secondary)

            Button {
                showExportAlert = true
            } label: {
                Label(strings.exportBackup, systemImage: "lock.doc")
            }
        }
    }

    private var aboutSection: some View {
        Section(header: Text(strings.sectionAbout)) {
            HStack {
                Text(strings.appVersion)
                Spacer()
            }
            Text(strings.offlineNotice)
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}