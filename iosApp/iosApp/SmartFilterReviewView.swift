import SharedLogic
import SwiftUI

/**
 * شاشة مراجعة الفلترة الذكية على SwiftUI (T7 — Issue #28 — spec 04 §ب): مرآة
 * SmartFilterReviewScreen.kt — اقتراحات القواعد مجمّعة بالتصنيف مع شفافية الدليل،
 * HIGH يُحدد مسبقاً (عقد الـ VM المشترك)، والتطبيق **بعد تأكيد صريح حصراً** من شريط
 * أسفل الشاشة. كل التفاعل عبر [SmartFilterReviewIntent] حصراً — لا منطق أعمال هنا.
 */
struct SmartFilterReviewView: View {

    var store: SmartFilterReviewViewModelStore
    let language: AppLanguage
    let onBack: () -> Void

    var body: some View {
        let strings = SmartFilterReviewLocalizedStrings(language: language)

        Group {
            if store.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView()
                    Text(strings.loading).foregroundColor(.secondary).padding(.top, 8)
                    Spacer()
                }
            } else if store.state.visibleItems.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "tray").font(.system(size: 40)).foregroundColor(.secondary)
                    Text(strings.emptyTitle).font(.headline)
                    Text(strings.emptyMessage)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    Spacer()
                }
            } else {
                groupsList(strings: strings)
                applyBar(strings: strings)
            }
        }
        .navigationTitle(strings.screenTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    store.back()
                } label: {
                    Image(systemName: "chevron.backward")
                }
                .accessibilityLabel(strings.backDescription)
            }
        }
        // عرض أرحام بعد تطبيق عائلة ذات مرشّح قرابة (AC — agy P1#6) — تأكيد صريح.
        .confirmationDialog(
            strings.arhamOfferTitle(Int32(store.state.pendingArhamOffer.count)),
            isPresented: Binding(
                get: { !store.state.pendingArhamOffer.isEmpty },
                set: { if !$0 && !store.state.pendingArhamOffer.isEmpty { store.declineArhamOffer() } }
            ),
            titleVisibility: .visible
        ) {
            Button(strings.arhamOfferAccept) {
                store.confirmArhamOffer()
            }
            Button(strings.cancel, role: .cancel) {
                store.declineArhamOffer()
            }
        }
        // فشل التطبيق/الجهة الغائبة (Effect عابر) — نص مترجم من الموارد حسب المفتاح.
        .alert(
            errorText(strings: strings),
            isPresented: Binding(
                get: { store.pendingErrorKey != nil },
                set: { if !$0 { store.clearError() } }
            )
        ) {
            Button(strings.backDescription, role: .cancel) {}
        }
        // معايرات النافذة قبل بدء الجمع (نمط T6).
        .onAppear {
            store.onNavigateBack = onBack
        }
        // مهمتان منفصلتان لا متتاليتان (نمط T4 المجرَّب — تدفقات لا نهائية).
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .onDisappear { store.clearViewModel() }
    }

    // MARK: - المحتوى

    @ViewBuilder
    private func groupsList(strings: SmartFilterReviewLocalizedStrings) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(store.state.groups, id: \.category) { group in
                    let visible = group.items.filter { !store.state.rejectedIds.contains($0.contactId) }
                    if !visible.isEmpty {
                        GroupHeaderSwift(
                            group: group,
                            visibleCount: Int32(visible.count),
                            strings: strings,
                            store: store
                        )
                        ForEach(visible, id: \.contactId) { item in
                            SuggestionItemRowSwift(
                                item: item,
                                checked: store.state.checkedIds.contains(item.contactId),
                                strings: strings,
                                store: store
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    /// شريط التطبيق المؤكد — المحدد حصراً (HIGH يُحدد مسبقاً في الـ VM).
    private func applyBar(strings: SmartFilterReviewLocalizedStrings) -> some View {
        VStack(spacing: 0) {
            Divider()
            Button {
                store.apply()
            } label: {
                Text(strings.applySelected(Int32(store.state.checkedItems.count)))
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!store.state.canApply)
            .padding(16)
        }
        .background(Color(.systemBackground))
    }

    private func errorText(strings: SmartFilterReviewLocalizedStrings) -> String {
        switch store.pendingErrorKey {
        case .applyFailed: return strings.errorApply
        case .contactNotFound: return strings.errorContactNotFound
        default: return strings.errorApply
        }
    }
}

/// رأس مجموعة تصنيف — الاسم بلون الفئة + العداد + تحديد الكل (نقرة ثانية تُلغي — عقد الـ VM).
private struct GroupHeaderSwift: View {

    let group: SmartFilterCategoryGroup
    let visibleCount: Int32
    let strings: SmartFilterReviewLocalizedStrings
    let store: SmartFilterReviewViewModelStore

    var body: some View {
        HStack {
            Text(strings.categoryTitle(group.category))
                .font(.caption.weight(.bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color.accentColor.opacity(0.14), in: Capsule())
                .foregroundColor(.accentColor)
            Text(strings.groupCount(visibleCount))
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Button(strings.selectAll) {
                store.toggleGroupAll(category: group.category)
            }
            .font(.caption)
            .buttonStyle(.borderless)
        }
    }
}

/// صف اقتراح — مربع تحديد + الاسم + الدليل والثقة، وفعلان صغيران: [ليست هذه الفئة]
/// و[لا تسألني مجدداً] (التغذية الراجعة المحلية — عقد docs/features/04 «يقلل وزنه»).
private struct SuggestionItemRowSwift: View {

    let item: SmartFilterItem
    let checked: Bool
    let strings: SmartFilterReviewLocalizedStrings
    let store: SmartFilterReviewViewModelStore

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(checked ? .accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.displayName).font(.subheadline).lineLimit(1)
                    HStack(spacing: 8) {
                        Text(strings.evidenceLabel(item.matchedToken))
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(confidenceLabel)
                            .font(.caption.weight(.medium))
                            .foregroundColor(confidenceColor)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .onTapGesture {
                store.toggleItem(contactId: item.contactId)
            }

            HStack {
                Spacer()
                Button(strings.notThisCategory) {
                    store.notThisCategory(contactId: item.contactId)
                }
                .font(.caption)
                .buttonStyle(.borderless)
                Button(strings.neverAsk) {
                    store.neverAsk(contactId: item.contactId)
                }
                .font(.caption)
                .buttonStyle(.borderless)
            }
            Divider()
        }
    }

    private var confidenceLabel: String {
        switch item.confidence {
        case .high: return strings.confidenceHigh
        case .medium: return strings.confidenceMedium
        case .low: return strings.confidenceLow
        @unknown default: return strings.confidenceLow
        }
    }

    private var confidenceColor: Color {
        switch item.confidence {
        case .high: return .accentColor
        case .medium: return .orange
        case .low: return .secondary
        @unknown default: return .secondary
        }
    }
}

/// غلاف الشاشة بنمط ContactEditScreen — ملكية المخزن عبر @State تصمد لإعادة بناء الجسم.
struct SmartFilterReviewScreen: View {

    @State private var store: SmartFilterReviewViewModelStore
    private let language: AppLanguage
    private let onBack: () -> Void

    init(language: AppLanguage, onBack: @escaping () -> Void) {
        _store = State(initialValue: SmartFilterReviewViewModelStore())
        self.language = language
        self.onBack = onBack
    }

    var body: some View {
        SmartFilterReviewView(
            store: store,
            language: language,
            onBack: onBack
        )
    }
}
