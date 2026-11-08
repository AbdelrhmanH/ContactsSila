import SwiftUI
import SharedLogic
import UniformTypeIdentifiers

/**
 * شاشة الإعدادات والتفضيلات لـ SwiftUI (T9 — Issue #30).
 * تطبق مبادئ Clean Architecture و Offline-First:
 * الواجهة تعرض UiState وترسل Intent فقط (AGENTS.md).
 * خالية تماماً من النصوص الثابتة، مع الالتزام بروابط الحالة الصريحة لـ SwiftUI.
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

    @State private var restorePassword = ""
    @State private var showRestoreAlert = false
    @State private var restoreModeIndex = 0
    @State private var restorePreferences = false
    @State private var importFileData: Data?
    @State private var showFileImporter = false

    /// وضع الاستعادة مشتق من الفهرس لتفادي الاعتماد على Hashable الناجمة عن SKIE
    private var restoreMode: RestoreMode {
        restoreModeIndex == 0 ? .merge : .replace
    }

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
            .alert(strings.exportBackup, isPresented: $showExportAlert) {
                SecureField(strings.backupPasswordHint, text: $exportPassword)
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
            .alert(strings.importBackup, isPresented: $showRestoreAlert) {
                SecureField(strings.restorePasswordHint, text: $restorePassword)
                Button(strings.previewButton) {
                    // إصلاح Issue #30: بايتات الملف الحقيقي التي اختارها المستخدم،
                    // وليس مصفوفة فارغة كما كان يحدث سابقاً.
                    guard
                        let usableData = SettingsViewModelStore.usableImportedBackupData(importFileData),
                        !restorePassword.isEmpty
                    else { return }
                    store.send(SettingsIntentPreviewBackupFile(
                        cipherBytes: SettingsViewModelStore.kotlinByteArray(from: usableData),
                        password: restorePassword
                    ))
                }
                .disabled(store.state.isImportingBackup)
                Button(strings.cancel, role: .cancel) {
                    importFileData = nil
                    restorePassword = ""
                }
            } message: {
                Text(backupImportMessage)
            }
        }
        .task {
            await store.observeState()
        }
        .task {
            await store.observeEffects()
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { result in
            handleBackupImportResult(result)
        }
        .sheet(isPresented: Binding(
            get: { store.state.showRestorePreviewDialog && store.state.backupPreview != nil },
            set: { visible in
                if !visible {
                    importFileData = nil
                    restorePassword = ""
                    store.send(SettingsIntentDismissRestorePreviewDialog.shared)
                }
            }
        )) {
            restorePreviewSheet
        }
        .alert("", isPresented: Binding(
            get: { store.toastMessage != nil },
            set: { presenting in
                if !presenting { store.dismissToast() }
            }
        )) {
            Button(strings.confirm, role: .cancel) {}
        } message: {
            Text(store.toastMessage ?? "")
        }
    }

    // MARK: - تدفق استيراد النسخة الاحتياطية

    private func handleBackupImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let isScoped = url.startAccessingSecurityScopedResource()
            defer { if isScoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                guard let usableData = SettingsViewModelStore.usableImportedBackupData(data) else {
                    store.presentToastMessage(strings.backupFileEmpty)
                    return
                }
                importFileData = usableData
                restorePassword = ""
                showRestoreAlert = true
            } catch {
                store.presentToastMessage(strings.backupFileReadFailed)
            }
        case .failure:
            break
        }
    }

    private var backupImportMessage: String {
        if let error = store.state.backupErrorMessage {
            // رسالة الخطأ الفعلية من فك التشفير (كلمة مرور غير صحيحة) تظهر مرئياً
            return error
        }
        return strings.restoreDialogDesc
    }

    private var restorePreviewSheet: some View {
        NavigationStack {
            Form {
                Section(header: Text(strings.previewDialogTitle)) {
                    if let preview = store.state.backupPreview {
                        Text(previewSummaryText(preview))
                        Text(strings.backupDescription)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                    if let error = store.state.backupErrorMessage {
                        Text(error)
                            .foregroundColor(.red)
                            .font(.footnote)
                    }
                }
                Section(header: Text(strings.restoreModeTitle)) {
                    Picker(strings.restoreModeTitle, selection: $restoreModeIndex) {
                        Text(strings.restoreModeMerge).tag(0)
                        Text(strings.restoreModeReplace).tag(1)
                    }
                    .pickerStyle(.segmented)
                    Text(restoreModeIndex == 0 ? strings.restoreModeMergeDesc : strings.restoreModeReplaceDesc)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                Section {
                    Toggle(strings.restorePreferences, isOn: $restorePreferences)
                }
                Section {
                    Button(strings.startRestore) {
                        startConfirmedRestore()
                    }
                    .disabled(store.state.isImportingBackup || importFileData == nil)
                    Button(strings.cancel, role: .cancel) {
                        store.send(SettingsIntentDismissRestorePreviewDialog.shared)
                    }
                }
            }
            .navigationTitle(strings.previewDialogTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        store.send(SettingsIntentDismissRestorePreviewDialog.shared)
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func startConfirmedRestore() {
        guard
            let usableData = importFileData,
            !restorePassword.isEmpty
        else { return }
        store.send(SettingsIntentConfirmRestore(
            cipherBytes: SettingsViewModelStore.kotlinByteArray(from: usableData),
            password: restorePassword,
            mode: restoreMode,
            restorePreferences: restorePreferences
        ))
    }

    private func previewSummaryText(_ preview: BackupPreview) -> String {
        String(
            format: strings.previewCountsFormat,
            Int(preview.categoriesCount),
            Int(preview.arhamCount),
            Int(preview.tagsCount),
            Int(preview.categoryRingtonesCount)
        )
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section(header: Text(strings.sectionAppearance)) {
            // السمة
            Picker(strings.theme, selection: Binding(
                get: { store.state.preferences.themeMode },
                set: { store.send(SettingsIntentSetThemeMode(mode: $0)) }
            )) {
                Text(strings.themeLight).tag(AppThemeMode.light)
                Text(strings.themeDark).tag(AppThemeMode.dark)
                Text(strings.themeSystem).tag(AppThemeMode.system)
            }

            // اللغة
            Picker(strings.languageTitle, selection: Binding(
                get: { store.state.preferences.language },
                set: { store.send(SettingsIntentSetLanguage(language: $0)) }
            )) {
                Text(strings.languageArabicName).tag(AppLanguage.arabic)
                Text(strings.languageEnglishName).tag(AppLanguage.english)
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
                Text(strings.tabContacts).tag(DefaultTab.contacts)
                Text(strings.tabServices).tag(DefaultTab.services)
                Text(strings.tabArham).tag(DefaultTab.arham)
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
                set: { store.send(SettingsIntentSetDuplicateScanEnabled(enabled: $0)) }
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

            Button {
                // إصلاح متابَع (اكتشفه agy · gemini flash-high): الزر كان يفتح تنبيه
                // كلمة المرور مباشرة وimportFileData صفر — مسار الاختيار غير مُوصل.
                showFileImporter = true
            } label: {
                Label(strings.importBackup, systemImage: "arrow.down.doc")
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
