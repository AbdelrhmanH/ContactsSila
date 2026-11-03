import SharedLogic
import SwiftUI

/**
 * شاشة تفاصيل جهة الاتصال على SwiftUI (T6 — Issue #27).
 *
 * Stateful رقيقة: تعرض [ContactDetailsUiState] المشترك وتُرسل [ContactDetailsIntent]
 * حصراً — لا منطق أعمال ولا لمس Room/المنصة إطلاقاً (نمط ContactsView من T4).
 * آثار المنصة (اتصال/رسالة/بريد/رابط/تطبيق متصل/مشاركة vCard) يجامعها
 * [ContactDetailsViewModelStore] حصرياً (قناة أحادية المستهلك)، وعرضها هنا عابر
 * فقط. الحقول الفارغة لا تُعرض (معيار قبول) — كل قسم يفحص بياناته قبل رسمه.
 *
 * التنقل (J-2): iOS يستعمل `NavigationStack(path:)` الأصلي — [onBack] و[onEdit]
 * معايرات تضبطها حاوية النافذة (ContentView) لتحريك المكدس؛ الـ VM يبث أثر
 * NavigateBack/NavigateToEdit والمعايرات تنفذه (لا إغلاق تنقل داخل الشاشة).
 */
struct ContactDetailsView: View {

    var store: ContactDetailsViewModelStore
    let language: AppLanguage
    let onBack: () -> Void
    let onEdit: (Int64) -> Void

    /// Sheet «تغيير التصنيف» — عرض عابر محلي (نفس نمط منتقي الأرقام في T4).
    @State private var showCategoryDialog = false

    var body: some View {
        let strings = ContactDetailsLocalizedStrings(language: language)

        Group {
            if store.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView()
                    Text(strings.loading).foregroundColor(.secondary).padding(.top, 8)
                    Spacer()
                }
            } else if store.state.isNotFound {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "person.slash").font(.system(size: 40)).foregroundColor(.secondary)
                    Text(strings.notFound).multilineTextAlignment(.center).padding(.horizontal, 24)
                    Button(strings.back) { onBack() }
                    Spacer()
                }
            } else if let contact = store.state.contact {
                detailsContent(for: contact, strings: strings)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarItems(for: strings) }
        // تغيير التصنيف — الأفعال عبر Intent المشترك حصراً.
        .confirmationDialog(
            strings.actionChangeCategory,
            isPresented: $showCategoryDialog,
            titleVisibility: .visible
        ) {
            ForEach(categoryOptions, id: \.self) { category in
                Button(strings.categoryTitle(category)) {
                    if let contact = store.state.contact {
                        store.changeCategory(contact.id, category)
                    }
                }
            }
            Button(strings.cancel, role: .cancel) {}
        }
        // مشاركة vCard (Effect عابر) عبر ورقة النظام — vCard 3.0 نصاً.
        .sheet(
            item: Binding(
                get: { store.pendingShareText.map { DetailsShareItem(text: $0) } },
                set: { if $0 == nil { store.clearPendingShare() } }
            )
        ) { item in
            DetailsShareSheet(text: item.text)
        }
        // فشل الحذف النهائي (Effect عابر) — نص مترجم من الموارد (لا نص ثابت).
        .alert(
            strings.deleteError,
            isPresented: Binding(
                get: { store.showDeleteError },
                set: { if !$0 { store.clearDeleteError() } }
            )
        ) {
            Button(strings.cancel, role: .cancel) {}
        }
        // معايرات النافذة قبل بدء الجمع (onAppear قبل task — نمط T4).
        .onAppear {
            store.onNavigateBack = onBack
            store.onNavigateToEdit = onEdit
        }
        // مهمتان منفصلتان لا متتاليتان (نمط T4 المجرَّب — تدفقات لا نهائية).
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .onDisappear { store.handleDisappear() }
    }

    // MARK: - المحتوى

    @ViewBuilder
    private func detailsContent(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        List {
            // شريط التراجع (J-5) — ظاهر فقط خلال نافذة الـ 5 ثوانٍ.
            if store.state.isUndoWindowActive {
                Section {
                    HStack {
                        Image(systemName: "trash")
                        Text(strings.deleteUndoMessage)
                        Spacer()
                        Button(strings.undo) { store.undoDelete() }
                    }
                    .font(.callout)
                }
            }

            headerSection(for: contact, strings: strings)
            quickActionsSection(for: contact, strings: strings)

            if !store.state.connectedApps.isEmpty {
                connectedAppsSection(strings: strings)
            }

            if store.state.arhamFrequency != nil {
                arhamSection(strings: strings)
            }

            if !store.state.recentInteractions.isEmpty {
                historySection(strings: strings)
            }

            if !contact.phones.isEmpty {
                phonesSection(for: contact, strings: strings)
            }

            if !contact.emails.isEmpty {
                emailsSection(for: contact, strings: strings)
            }

            if !contact.websites.isEmpty {
                websitesSection(for: contact, strings: strings)
            }

            if contact.organization != nil {
                companySection(for: contact, strings: strings)
            }

            if !contact.addresses.isEmpty {
                addressesSection(for: contact, strings: strings)
            }

            if !contact.dates.isEmpty {
                datesSection(for: contact, strings: strings)
            }

            if let notes = contact.notes, !notes.isEmpty {
                Section(strings.notesSection) {
                    Text(notes)
                }
            }

            if !contact.customFields.isEmpty {
                customFieldsSection(for: contact, strings: strings)
            }

            if let account = contact.account {
                Section(strings.accountSection) {
                    Text(account.accountName ?? strings.accountDevice)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    /// رأس الصفحة: الصورة الرمزية (أحرف أولى — نمط T3/T4)، الاسم، التصنيف، المفضلة.
    private func headerSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section {
            HStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(Color.emeraldSila.opacity(0.15))
                    Text(contactsInitialLetter(contact))
                        .font(.title.weight(.semibold))
                        .foregroundColor(Color.emeraldSila)
                }
                .frame(width: 64, height: 64)

                VStack(alignment: .leading, spacing: 4) {
                    Text(contact.name.displayName)
                        .font(.title2.weight(.semibold))
                    HStack(spacing: 8) {
                        Text(strings.categoryTitle(contact.category))
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.emeraldSila.opacity(0.12), in: Capsule())
                        if contact.isFavorite {
                            Image(systemName: "star.fill")
                                .font(.caption)
                                .foregroundColor(.yellow)
                        }
                    }
                }
                Spacer()
            }
        }
    }

    /// الأفعال السريعة: اتصال/رسالة/بريد — الرقم الافتراضي يُحل في الـ VM حصراً.
    private func quickActionsSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section {
            HStack(spacing: 0) {
                quickButton(title: strings.call, icon: "phone.fill") { store.call(contact) }
                if !contact.phones.isEmpty {
                    Divider()
                    quickButton(title: strings.message, icon: "message.fill") {
                        store.message(contact.phones[0].e164)
                    }
                }
                if let email = contact.emails.first {
                    Divider()
                    quickButton(title: strings.email, icon: "envelope.fill") { store.email(email.address) }
                }
            }
            .buttonStyle(.plain)
        }
    }

    private func quickButton(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                Text(title).font(.caption)
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// التطبيقات المتصلة المثبتة فعلاً — أزرارها تظهر لها فقط (AC).
    private func connectedAppsSection(strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.appsSection) {
            ForEach(store.state.connectedApps, id: \.id) { app in
                Button {
                    if let contact = store.state.contact, let phone = contact.phones.first {
                        store.openConnectedApp(app, e164: phone.e164)
                    }
                } label: {
                    HStack {
                        Text(strings.connectedAppName(app.id))
                        Spacer()
                        Image(systemName: "arrow.up.right.square").foregroundColor(.secondary)
                    }
                }
            }
        }
    }

    /// أرحام — الوتيرة وآخر اتصال (القرابة تحريرها تفصيل T8 — مرآة Compose حرفياً،
    /// الووتيرة من حالة الـ VM `arhamFrequency` وأيام آخر اتصال بساعة النطاق).
    private func arhamSection(strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.arhamSection) {
            if let frequency = store.state.arhamFrequency {
                HStack(spacing: 8) {
                    Text(strings.arhamFrequencyLabel(frequency))
                    Spacer()
                    if let days = store.state.arhamDaysSinceLastContact {
                        Text(strings.arhamDaysLabel(days.value))
                            .font(.callout)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
    }

    /// «آخر التواصلات» — قراءة Room حصراً (بلا READ_CALL_LOG في مسار التفاصيل).
    private func historySection(strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.historySection) {
            ForEach(store.state.recentInteractions, id: \.callLogId) { interaction in
                HStack {
                    Image(systemName: iconName(for: interaction.direction))
                        .foregroundColor(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(formatRelativeInteractionTime(
                            interaction.timestampMillis,
                            nowMillis: currentMillis(),
                            strings: strings,
                            months: strings.months
                        ))
                        .font(.callout)
                        if interaction.durationSec > 0 {
                            Text(formatCallDuration(interaction.durationSec))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    Text(strings.stringForKey(directionKey(interaction.direction)))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private func phonesSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.phonesSection) {
            ForEach(contact.phones, id: \.e164) { phone in
                HStack {
                    Text(phone.e164)
                        .environment(\.layoutDirection, .leftToRight)
                    Spacer()
                    Text(strings.phoneTypeName(phone.type))
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if phone.isPrimary {
                        Image(systemName: "star.fill").font(.caption2).foregroundColor(.yellow)
                    }
                }
                .contentShape(Rectangle())
                .contextMenu {
                    Button {
                        UIPasteboard.general.string = phone.e164
                    } label: {
                        Label(strings.copyNumber, systemImage: "doc.on.doc")
                    }
                    Button {
                        store.callNumber(phone.e164)
                    } label: {
                        Label(strings.call, systemImage: "phone.fill")
                    }
                    Button {
                        store.message(phone.e164)
                    } label: {
                        Label(strings.message, systemImage: "message.fill")
                    }
                }
            }
        }
    }

    private func emailsSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.emailsSection) {
            ForEach(contact.emails, id: \.address) { email in
                Button {
                    store.email(email.address)
                } label: {
                    HStack {
                        Text(email.address)
                        Spacer()
                        Text(strings.emailTypeName(email.type))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
    }

    private func websitesSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.websitesSection) {
            ForEach(contact.websites, id: \.self) { url in
                Button {
                    store.openWebsite(url)
                } label: {
                    Text(url)
                        .environment(\.layoutDirection, .leftToRight)
                }
            }
        }
    }

    private func companySection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.companySection) {
            if let org = contact.organization {
                if let company = org.company { Text(company) }
                if let department = org.department {
                    Text(department).font(.callout).foregroundColor(.secondary)
                }
                if let title = org.jobTitles.first {
                    Text(title).font(.callout).foregroundColor(.secondary)
                }
            }
        }
    }

    private func addressesSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.addressesSection) {
            ForEach(Array(contact.addresses.enumerated()), id: \.offset) { _, address in
                VStack(alignment: .leading, spacing: 2) {
                    Text(addressLines(address))
                        .environment(\.layoutDirection, .rightToLeft)
                    Text(strings.addressTypeName(address.type))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private func datesSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.datesSection) {
            ForEach(Array(contact.dates.enumerated()), id: \.offset) { _, date in
                HStack {
                    Text(strings.dateTypeName(date.type))
                    Spacer()
                    Text(dateText(for: date))
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private func customFieldsSection(for contact: Contact, strings: ContactDetailsLocalizedStrings) -> some View {
        Section(strings.customFieldsSection) {
            ForEach(Array(contact.customFields.enumerated()), id: \.offset) { _, field in
                HStack {
                    Text(field.label)
                    Spacer()
                    Text(field.value).foregroundColor(.secondary)
                }
            }
        }
    }

    // MARK: - الشريط العلوي

    @ToolbarContentBuilder
    private func toolbarItems(for strings: ContactDetailsLocalizedStrings) -> some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            Button { onBack() } label: {
                Image(systemName: "chevron.backward")
            }
            .accessibilityLabel(strings.back)
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            if let contact = store.state.contact {
                Button {
                    store.toggleFavorite(contact)
                } label: {
                    Image(systemName: contact.isFavorite ? "star.fill" : "star")
                }
                .accessibilityLabel(contact.isFavorite ? strings.unfavorite : strings.favorite)

                Button {
                    store.edit(contact.id)
                } label: {
                    Image(systemName: "pencil")
                }
                .accessibilityLabel(strings.edit)

                Menu {
                    Button {
                        store.shareVCard(contact.id)
                    } label: {
                        Label(strings.actionShareVCard, systemImage: "square.and.arrow.up")
                    }
                    Button {
                        showCategoryDialog = true
                    } label: {
                        Label(strings.actionChangeCategory, systemImage: "tag")
                    }
                    Button(role: .destructive) {
                        store.requestDelete()
                    } label: {
                        Label(strings.actionDelete, systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .accessibilityLabel(strings.more)
            }
        }
    }

    // MARK: - أدوات عرض نقية

    private var categoryOptions: [SharedLogic.Category] {
        [
            SharedLogic.Category.family,
            SharedLogic.Category.friends,
            SharedLogic.Category.work,
            SharedLogic.Category.services,
            SharedLogic.Category.emergency,
            SharedLogic.Category.uncategorized
        ]
    }

    private func iconName(for direction: CallDirection) -> String {
        switch direction {
        case .`in`: return "arrow.down.left"
        case .out: return "arrow.up.right"
        default: return "phone.badge.xmark"
        }
    }

    private func addressLines(_ address: Address) -> String {
        [address.street, address.city, address.region, address.postalCode, address.country]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: "، ")
    }

    private func dateText(for date: ImportantDate) -> String {
        let year = date.year.map(String.init) ?? ""
        return "\(date.day)/\(date.month)/\(year)".trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
    }

    private func currentMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000.0)
    }
}

// MARK: - حاوية الشاشة (هوية المخزن ثابتة لكل دفعة — نمط State initialValue)

/**
 * حاوية شاشة التفاصيل — تبني [ContactDetailsViewModelStore] مرة واحدة لهوية الدفعة
 * (`@State` initialValue) فتنجو من إعادة البناء عند إعادة تقييم navigationDestination،
 * وتفك الـ VM عند الخروج النهائي (J-5).
 */
struct ContactDetailsScreen: View {

    @State private var store: ContactDetailsViewModelStore
    private let language: AppLanguage
    private let onBack: () -> Void
    private let onEdit: (Int64) -> Void

    init(contactId: Int64, language: AppLanguage, onBack: @escaping () -> Void, onEdit: @escaping (Int64) -> Void) {
        _store = State(initialValue: ContactDetailsViewModelStore(contactId: contactId))
        self.language = language
        self.onBack = onBack
        self.onEdit = onEdit
    }

    var body: some View {
        ContactDetailsView(store: store, language: language, onBack: onBack, onEdit: onEdit)
    }
}

// MARK: - مشاركة vCard (Effect عابر — نمط T4)

private struct DetailsShareItem: Identifiable {
    let text: String
    var id: String { text }
}

private struct DetailsShareSheet: UIViewControllerRepresentable {

    let text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
