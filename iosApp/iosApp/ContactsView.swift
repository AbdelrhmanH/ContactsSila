import Contacts
import ContactsUI
import SharedLogic
import SwiftUI

/**
 * شاشة جهات الاتصال على SwiftUI (T4 — Issue #25).
 *
 * Stateful رقيقة: تعرض [ContactsUiState] المشترك وتُرسل [ContactsIntent] حصراً —
 * لا منطق أعمال ولا فرز ولا تصفية ولا لمس Room/المنصة إطلاقاً (نمط `ContactsScreen.kt`
 * من T3 على Compose). آثار المنصة (اتصال/رسالة/مشاركة/إعدادات/منتقي أرقام) يجامعها
 * [ContactsViewModelStore] حصرياً (قناة أحادية المستهلك)، وعرضها هنا عابر فقط.
 *
 * الاتجاه والسمة وديناميكية الخط تُورَّث من البيئة (RTL/LTR من لغة الحالة المشتركة
 * عبر `ContentView`، والوضع الداكن من سمات النظام).
 */
struct ContactsView: View {

    var store: ContactsViewModelStore
    let language: AppLanguage

    @Environment(\.scenePhase) private var scenePhase

    /// تأكيد الحذف عرض عابر محلي (نفس `remember` في Compose) — الفعل عبر Intent.
    @State private var showDeleteConfirmation = false

    /// مسبار إعادة قراءة حالة الوصول الجزئي عند العودة للتطبيق (بعد الإعدادات).
    @State private var permissionProbeTick = 0

    var body: some View {
        let strings = ContactsLocalizedStrings(language: language)

        VStack(spacing: 0) {
            if store.state.isSelectionMode {
                ContactsSelectionBar(
                    count: Int32(store.state.selection.count),
                    strings: strings,
                    onCategorize: { store.bulkCategorize($0) },
                    onFavorite: { store.bulkSetFavorite(true) },
                    onUnfavorite: { store.bulkSetFavorite(false) },
                    onShare: { store.shareSelection() },
                    onDelete: { showDeleteConfirmation = true },
                    onDone: { store.clearSelection() }
                )
            } else {
                ContactsHeaderRow(
                    title: strings.screenTitle,
                    selectedFilter: store.state.accountFilter,
                    filterTitle: { strings.accountFilterTitle($0) },
                    onSelectFilter: { store.setAccountFilter($0) }
                )
            }

            switch contactsScreenPhase(for: store.state) {
            case .permissionDenied:
                // رفض الصلاحية يطغى على كل الشاشة — لا قائمة أصلاً بلا مصدر بيانات.
                ContactsStateMessage(
                    title: strings.permissionDeniedTitle,
                    message: strings.permissionDeniedMessage,
                    actionTitle: strings.openSettings,
                    onAction: { store.openSettings() }
                )
                .accessibilityIdentifier("contacts_permission_denied")

            case .error:
                ContactsStateMessage(
                    title: strings.errorTitle,
                    message: strings.errorMessage,
                    actionTitle: strings.retry,
                    onAction: { store.retry() }
                )

            case .content:
                contactsContent(strings)
            }
        }
        // منتقي الأرقام (Effect عابر) — الإرسال بالرقم المختار عبر العقد المشترك.
        .confirmationDialog(
            strings.numberPickerTitle,
            isPresented: Binding(
                get: { store.numberPicker != nil },
                set: { if !$0 { store.cancelNumberPicker() } }
            ),
            titleVisibility: .visible,
            presenting: store.numberPicker
        ) { picker in
            ForEach(Array(picker.phones.enumerated()), id: \.offset) { _, phone in
                Button("\(phone.e164)\(phone.isPrimary ? " ★" : "")") {
                    store.pickNumber(phone)
                }
            }
            Button(strings.cancel, role: .cancel) {
                store.cancelNumberPicker()
            }
        }
        // تأكيد الحذف حصراً قبل BulkDelete (عقد T3).
        .confirmationDialog(
            strings.deleteConfirmationTitle,
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(strings.confirmDelete, role: .destructive) {
                store.bulkDelete()
            }
            Button(strings.cancel, role: .cancel) {}
        } message: {
            Text(strings.deleteConfirmationMessage(Int32(store.state.selection.count)))
        }
        // مشاركة نص الاختيار (Effect عابر) عبر ورقة النظام.
        .sheet(
            item: Binding(
                get: { store.pendingShareText.map { ContactsShareItem(text: $0) } },
                set: { if $0 == nil { store.clearPendingShare() } }
            )
        ) { item in
            ContactsShareSheet(text: item.text)
        }
        // مهمتان منفصلتان لا متتاليتان: observeState تنتظر تدفقاً لا نهائياً —
        // أي انتظار متتابع بعدها يبقى بلا أثر أبداً (نمط OnboardingView المجرب).
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { permissionProbeTick += 1 }
        }
    }

    // MARK: - المحتوى (بانر .limited + مفضلة + بحث + شرائح + اقتراحات + القائمة)

    @ViewBuilder
    private func contactsContent(_ strings: ContactsLocalizedStrings) -> some View {
        VStack(spacing: 0) {
            ContactsLimitedBanner(
                strings: strings,
                permissionProbeTick: permissionProbeTick,
                queryString: store.state.searchQuery
            )
            ContactsFavoritesRow(
                favorites: store.state.favorites,
                strings: strings
            )
            ContactsSearchField(
                text: Binding(
                    get: { store.state.searchQuery },
                    set: { store.updateSearchQuery($0) }
                ),
                placeholder: strings.searchPlaceholder
            )
            ContactsCategoryChipsRow(
                selectedCategory: store.state.selectedCategory,
                categoryCounts: store.state.categoryCounts,
                totalContactsCount: store.state.totalContactsCount,
                strings: strings,
                onSelect: { store.selectCategory($0) }
            )
            ContactsSuggestionCards(
                suggestions: store.state.suggestions,
                strings: strings,
                onDismiss: { store.dismissSuggestion($0) },
                onOpenSmartFilter: { store.selectSmartFilterSuggestion() }
            )
            contactsListArea(strings)
        }
    }

    @ViewBuilder
    private func contactsListArea(_ strings: ContactsLocalizedStrings) -> some View {
        switch contactsListPhase(for: store.state) {
        case .loading:
            VStack {
                Spacer()
                ProgressView()
                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

        case .empty:
            ContactsStateMessage(title: strings.emptyContactsTitle, message: strings.emptyContactsMessage)

        case .noResults:
            ContactsStateMessage(title: strings.emptySearchTitle, message: strings.emptySearchMessage)

        case .loaded:
            ContactsGroupedList(
                state: store.state,
                strings: strings,
                onToggleSelect: { store.toggleSelect($0) },
                onToggleFavorite: { store.toggleFavorite($0) },
                onCall: { store.call($0) },
                onSwipe: { store.swipe($0, $1) }
            )
        }
    }
}

// MARK: - الرأس وفلتر الحساب

private struct ContactsHeaderRow: View {

    let title: String
    let selectedFilter: AccountFilter
    let filterTitle: (AccountFilter) -> String
    let onSelectFilter: (AccountFilter) -> Void

    /// مرآة `AccountFilter.entries` في Compose — قائمة ثابتة بلا منطق.
    private static let filters: [AccountFilter] = [.all, .device, .google, .other]

    var body: some View {
        HStack {
            Text(title)
                .font(.title2.weight(.bold))
            Spacer()
            Menu {
                ForEach(Self.filters, id: \.self) { filter in
                    Button(filterTitle(filter)) {
                        onSelectFilter(filter)
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(filterTitle(selectedFilter))
                        .font(.subheadline)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                }
            }
            .accessibilityIdentifier("contacts_account_filter")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: - شريط التحديد المتعدد

private struct ContactsSelectionBar: View {

    let count: Int32
    let strings: ContactsLocalizedStrings
    // تأهيل SharedLogic.Category صراحةً: ObjectiveC.Category في iOS 18.2 SDK
    // (Xcode 16.2) يتصادم مع enum الـ SKIE المولَّد في كل ملف يستورد SharedLogic.
    let onCategorize: (SharedLogic.Category) -> Void
    let onFavorite: () -> Void
    let onUnfavorite: () -> Void
    let onShare: () -> Void
    let onDelete: () -> Void
    let onDone: () -> Void

    /// مرآة `Category.entries` في Compose — قائمة ثابتة بلا منطق.
    private static let categories: [SharedLogic.Category] = [
        .family, .friends, .work, .services, .emergency, .uncategorized,
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Text(strings.selectionTitle(count))
                    .font(.headline)
                Menu {
                    ForEach(Self.categories, id: \.self) { category in
                        Button(strings.categoryTitle(category)) {
                            onCategorize(category)
                        }
                    }
                } label: {
                    Text(strings.bulkCategorize)
                }
                // الرمزان القلبيان زخرفة بصرية؛ الدلالة للقارئ الآلي عبر أوصاف
                // مُترجَمة (مراجعة PR #36 — F4: لا نصوص ثابتة بلا دلالة موارد).
                Button(action: onFavorite) {
                    Text("♥").foregroundStyle(.secondary)
                }
                .accessibilityLabel(strings.bulkFavorite)
                Button(action: onUnfavorite) {
                    Text("♡").foregroundStyle(.secondary)
                }
                .accessibilityLabel(strings.bulkUnfavorite)
                Button(strings.bulkShare, action: onShare)
                Button(strings.bulkDelete, action: onDelete)
                Button(strings.selectionDone, action: onDone)
            }
            .font(.subheadline)
            .buttonStyle(.borderless)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(.secondarySystemBackground))
        .accessibilityIdentifier("contacts_selection_bar")
    }
}

// MARK: - بانر الوصول الجزئي .limited (ADR-0009 — iOS 18+ حصراً)

/**
 * `.limited` حالة صلاحية أصيلة من الدرجة الأولى: يُشرَح بانر ولا يُعرض أبداً كحذف
 * أو نقص بيانات، مع زر توسيع النطاق `ContactAccessButton` (متاح iOS 18+ فقط —
 * الحالة نفسها لا تحدث قبل iOS 18). هذه قراءة سطح الصلاحية للمنصة (عرض فقط)،
 * والمزامنة نفسها تعالج تغيّر النطاق في Kotlin (ADR-0010 §5).
 */
private struct ContactsLimitedBanner: View {

    let strings: ContactsLocalizedStrings
    /// قراءة عند كل تغيير مسبار كي تُعاد الحالة بعد العودة من إعدادات النظام.
    let permissionProbeTick: Int
    /// استعلام البحث الحي — توقيع `ContactAccessButton` الفعلي (iOS 18) يأخذ
    /// `queryString` بلا أي بارامتر `for:` — ليتزامن العرض مع حقل البحث.
    let queryString: String

    var body: some View {
        if isLimitedAccess {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "person.crop.circle.badge.exclamationmark")
                    .foregroundStyle(.secondary)
                Text(strings.limitedBanner)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                if #available(iOS 18.0, *) {
                    ContactAccessButton(queryString: queryString)
                        .font(.footnote)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
            .accessibilityIdentifier("contacts_limited_banner")
        }
    }

    private var isLimitedAccess: Bool {
        _ = permissionProbeTick
        // .limited حصري لـ iOS 18+ (هدف النشر 17.0) — يُعالج بفحص توافر صريح
        // (نفس نمط OnboardingViewModelStore المجرب في المرحلة 0).
        if #available(iOS 18.0, *) {
            return CNContactStore.authorizationStatus(for: .contacts) == .limited
        }
        return false
    }
}

// MARK: - صف المفضلة

private struct ContactsFavoritesRow: View {

    let favorites: [Contact]
    let strings: ContactsLocalizedStrings

    var body: some View {
        if !favorites.isEmpty {
            // معيار القبول: يختفي تماماً بلا مفضلات.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(favorites, id: \.id) { contact in
                        VStack(spacing: 2) {
                            ContactsAvatar(
                                initial: contactsInitialLetter(contact),
                                diameter: 52,
                                ringColor: ringColor(for: contact)
                            )
                            .accessibilityLabel(contact.name.displayName)
                            Text(contact.name.displayName)
                                .font(.caption2)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .frame(width: 56)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
        }
    }

    /// حلقة حالة أرحام لأفراد العائلة (تصميم 02 — لون التصنيف «عائلة» #E5484D)
    /// ولون القلب المفضل (#D93F3F) — ثوابت دلالية بدل RGB مضمّن (مراجعة agy — F3).
    /// (بنطاق الملف — النوعان الخاصان في نفس الملف يستهلكانهما.)
    static let familyRingColor = Color(red: 229 / 255, green: 72 / 255, blue: 77 / 255)
    static let favoriteHeartColor = Color(red: 217 / 255, green: 63 / 255, blue: 63 / 255)

    private func ringColor(for contact: Contact) -> Color {
        contact.category == SharedLogic.Category.family
            ? Self.familyRingColor
            : Color(.systemGray4)
    }
}

// MARK: - البحث

private struct ContactsSearchField: View {

    let text: Binding<String>
    let placeholder: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Color(.secondarySystemBackground), in: Capsule())
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("contacts_search_field")
    }
}

// MARK: - شرائح التصنيف

private struct ContactsCategoryChipsRow: View {

    let selectedCategory: SharedLogic.Category?
    let categoryCounts: [SharedLogic.Category: KotlinInt]
    let totalContactsCount: Int32
    let strings: ContactsLocalizedStrings
    let onSelect: (SharedLogic.Category?) -> Void

    /// مرآة `Category.entries` في Compose — قائمة ثابتة بلا منطق.
    private static let categories: [SharedLogic.Category] = [
        .family, .friends, .work, .services, .emergency, .uncategorized,
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ContactsCategoryChip(
                    title: "\(strings.categoryAll) \(totalContactsCount)",
                    isSelected: selectedCategory == nil
                ) {
                    onSelect(nil)
                }
                ForEach(Self.categories, id: \.self) { category in
                    ContactsCategoryChip(
                        title: "\(strings.categoryTitle(category)) \(categoryCounts[category]?.intValue ?? 0)",
                        isSelected: selectedCategory == category
                    ) {
                        onSelect(category)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
        .accessibilityIdentifier("contacts_category_chips")
    }
}

private struct ContactsCategoryChip: View {

    let title: String
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            Text(title)
                .font(.footnote.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(isSelected ? Color.emeraldSila : Color.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    isSelected ? Color.emeraldSila.opacity(0.15) : Color(.secondarySystemBackground),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - بطاقات الاقتراح القابلة للإغلاق

private struct ContactsSuggestionCards: View {

    let suggestions: [SuggestionCard]
    let strings: ContactsLocalizedStrings
    let onDismiss: (SuggestionCard) -> Void
    let onOpenSmartFilter: () -> Void

    var body: some View {
        if !suggestions.isEmpty {
            VStack(spacing: 4) {
                ForEach(suggestions, id: \.id) { card in
                    HStack(spacing: 8) {
                        Text(title(for: card))
                            .font(.footnote)
                        Spacer(minLength: 8)
                        Button(strings.suggestionDismiss) {
                            onDismiss(card)
                        }
                        .font(.footnote)
                        .buttonStyle(.borderless)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
                    .onTapGesture {
                        // «راجعها»: بطاقة الفلترة الذكية تنقلك لتصنيف غير المصنّف؛
                        // بطاقة المكررات تنتظر شاشات الدمج (T7) — بلا فعل الآن
                        // (نفس سلوك Compose T3 الموثق في PR).
                        if card.kind == .smartFilter {
                            onOpenSmartFilter()
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }

    private func title(for card: SuggestionCard) -> String {
        switch card.kind {
        case .duplicates: return strings.suggestionDuplicates(card.count)
        case .smartFilter: return strings.suggestionSmartFilter(card.count)
        @unknown default: return ""
        }
    }
}

// MARK: - القائمة المجمعة حرفياً + الفهرس الجانبي

private struct ContactsGroupedList: View {

    let state: ContactsUiState
    let strings: ContactsLocalizedStrings
    let onToggleSelect: (Contact) -> Void
    let onToggleFavorite: (Contact) -> Void
    let onCall: (Contact) -> Void
    let onSwipe: (Contact, ContactSwipeAction) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            // تجميع واحد لكل إعادة رسم (مرآة `remember(state.contacts)` في Compose) —
            // O(n) نقية ومفتاح الصفوف/الرؤوس ثابت `contact.id`/`header-$letter`.
            let sections = buildContactsLetterSections(from: state.contacts)
            ZStack(alignment: .trailing) {
                List {
                    ForEach(sections) { section in
                        Section {
                            ForEach(section.contacts, id: \.id) { contact in
                                rowCell(contact)
                            }
                        } header: {
                            Text(section.letter)
                                // هوية القفز للفهرس الجانبي (نفس مفتاح `header-$letter`).
                                .id(section.letter)
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Color.emeraldSila)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textCase(nil)
                                .listRowBackground(Color(.systemBackground))
                        }
                    }
                }
                .listStyle(.plain)
                // مساحة الـ FAB الذكي في الغلاف (نفس حشوة 88dp في Compose).
                .contentMargins(.bottom, 96, for: .scrollContent)
                .accessibilityIdentifier("contacts_list")

                if sections.count >= 2 {
                    ContactsLetterIndexBar(
                        letters: sections.map(\.letter)
                    ) { letter in
                        proxy.scrollTo(letter, anchor: .top)
                    }
                }
            }
        }
    }

    /// السحب معطّل في وضع التحديد (يد ينظفان — لا تضارب إيماءات، نفس Compose).
    @ViewBuilder
    private func rowCell(_ contact: Contact) -> some View {
        if state.isSelectionMode {
            ContactsRow(
                contact: contact,
                state: state,
                strings: strings,
                onToggleSelect: onToggleSelect,
                onToggleFavorite: onToggleFavorite,
                onCall: onCall
            )
        } else {
            ContactsRow(
                contact: contact,
                state: state,
                strings: strings,
                onToggleSelect: onToggleSelect,
                onToggleFavorite: onToggleFavorite,
                onCall: onCall
            )
            // دلالة البداية/النهاية منطقية من تفضيلات المستخدم وتنعكس تلقائياً
            // في RTL (نفس دلالة SwipeToDismissBox المنطقية في Compose)؛ الفعل
            // إطلاق لا إزالة — الصف يعود مستقراً.
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button {
                    onSwipe(contact, state.swipeStartAction)
                } label: {
                    Text(swipeTitle(state.swipeStartAction))
                }
                .tint(Color.emeraldSila.opacity(0.6))
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button {
                    onSwipe(contact, state.swipeEndAction)
                } label: {
                    Text(swipeTitle(state.swipeEndAction))
                }
                .tint(Color(.systemGray))
            }
        }
    }

    private func swipeTitle(_ action: ContactSwipeAction) -> String {
        switch action {
        case .message: return strings.swipeMessage
        default: return strings.swipeCall
        }
    }
}

// MARK: - صف جهة الاتصال

private struct ContactsRow: View {

    let contact: Contact
    let state: ContactsUiState
    let strings: ContactsLocalizedStrings
    let onToggleSelect: (Contact) -> Void
    let onToggleFavorite: (Contact) -> Void
    let onCall: (Contact) -> Void

    private var isSelected: Bool {
        state.selection.contains(KotlinLong(value: contact.id))
    }

    var body: some View {
        HStack(spacing: 12) {
            if state.isSelectionMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? Color.emeraldSila : Color.secondary)
            } else {
                ContactsAvatar(initial: contactsInitialLetter(contact), diameter: 44, ringColor: nil)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name.displayName)
                    .font(.body)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(strings.categoryTitle(contact.category))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            Button {
                onToggleFavorite(contact)
            } label: {
                Text(contact.isFavorite ? "♥" : "♡")
                    .foregroundStyle(
                        contact.isFavorite
                            ? ContactsFavoritesRow.favoriteHeartColor
                            : Color.secondary
                    )
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(contact.isFavorite ? strings.bulkUnfavorite : strings.bulkFavorite)

            Button {
                onCall(contact)
            } label: {
                Text("📞")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(strings.callRow)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        // نقرة الصف في وضع العادي = «التفاصيل» — لكن شاشة/ورقة التفاصيل نطاق T6،
        // فالنقرة العادية مؤجلة عمداً ولا تبدأ اتصالاً (نفس قرار T3 الموثق).
        .onTapGesture {
            if state.isSelectionMode {
                onToggleSelect(contact)
            }
        }
        // ضغطة مطولة تفتح الاختيار المتعدد وتحدد الصف المضغوط (عقد T3).
        .onLongPressGesture {
            onToggleSelect(contact)
        }
        .accessibilityHint(strings.selectContact)
    }
}

// MARK: - عناصر مشتركة صغيرة

private struct ContactsAvatar: View {

    let initial: String
    let diameter: CGFloat
    let ringColor: Color?

    var body: some View {
        Text(initial)
            .font(.body.weight(.medium))
            .frame(width: diameter, height: diameter)
            .background(Color(.tertiarySystemFill), in: Circle())
            .overlay(
                Circle()
                    .strokeBorder(ringColor ?? .clear, lineWidth: ringColor == nil ? 0 : 2)
            )
    }
}

private struct ContactsLetterIndexBar: View {

    let letters: [String]
    let onTap: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(letters, id: \.self) { letter in
                Button {
                    onTap(letter)
                } label: {
                    Text(letter)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color.emeraldSila)
                        .padding(.vertical, 1)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 6)
        .background(Color(.systemBackground).opacity(0.85), in: RoundedRectangle(cornerRadius: 10))
        .padding(.trailing, 4)
        .accessibilityIdentifier("contacts_letter_index")
    }
}

/// حالات الفراغ/الخطأ/الصلاحية — عنوان + وصف + فعل اختياري (نفس StateMessage في Compose).
private struct ContactsStateMessage: View {

    let title: String
    let message: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 8) {
            Spacer()
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let actionTitle, let onAction {
                Button(actionTitle, action: onAction)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)
            }
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - ورقة المشاركة (Effect عابر)

private struct ContactsShareItem: Identifiable {
    let text: String
    var id: String { text }
}

private struct ContactsShareSheet: UIViewControllerRepresentable {

    let text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
