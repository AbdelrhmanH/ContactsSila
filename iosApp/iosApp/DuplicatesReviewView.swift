import SharedLogic
import SwiftUI

/**
 * شاشة مراجعة المكررات على SwiftUI (T7 — Issue #28 — spec 04 §أ «شاشة المكررات»).
 *
 * Stateful رقيقة: تعرض [DuplicatesReviewUiState] المشترك وتُرسل [DuplicatesReviewIntent]
 * حصراً — مرآة DuplicatesReviewScreen.kt: بطاقة لكل مجموعة (شريحة الثقة + الدليل +
 * الأعضاء)، التوسيع يكشف معاينة الدمج قبل الالتزام، «دمج كل المؤكدة» (HIGH حصراً)،
 * وشريط التراجع الموحد أسفل الشاشة. الرجوع عبر معايرات النافذة (J-2) — لا إغلاق
 * تنقل داخل الشاشة.
 */
struct DuplicatesReviewView: View {

    var store: DuplicatesReviewViewModelStore
    let language: AppLanguage
    let onBack: () -> Void
    let onOpenMember: (Int64) -> Void

    var body: some View {
        let strings = DuplicatesReviewLocalizedStrings(language: language)

        Group {
            if store.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView()
                    Text(strings.loading).foregroundColor(.secondary).padding(.top, 8)
                    Spacer()
                }
            } else if store.state.groups.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "checkmark.seal").font(.system(size: 40)).foregroundColor(.secondary)
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
        // فشل الدمج/التراجع (Effect عابر) — نص مترجم من الموارد حسب المفتاح.
        .alert(
            errorText(strings: strings),
            isPresented: Binding(
                get: { store.pendingErrorKey != nil },
                set: { if !$0 { store.clearError() } }
            )
        ) {
            Button(strings.undo, role: .cancel) {}
        }
        // شريط التراجع الموحد (ADR-0013 جلسة الدمج) — يطفو أسفل الشاشة.
        .overlay(alignment: .bottom) {
            if store.state.isUndoBarVisible {
                undoBar(strings: strings)
            }
        }
        // معايرات النافذة قبل بدء الجمع (نمط T6).
        .onAppear {
            store.onNavigateBack = onBack
            store.onOpenMember = onOpenMember
        }
        // مهمتان منفصلتان لا متتاليتان (نمط T4 المجرَّب — تدفقات لا نهائية).
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .onDisappear { store.clearViewModel() }
    }

    // MARK: - المحتوى

    @ViewBuilder
    private func groupsList(strings: DuplicatesReviewLocalizedStrings) -> some View {
        ScrollView {
            VStack(spacing: 8) {
                if !store.state.confirmedHighGroups.isEmpty {
                    Button {
                        store.mergeAllConfirmed()
                    } label: {
                        Text(strings.mergeAllConfirmed(Int32(store.state.confirmedHighGroups.count)))
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(store.state.isMerging)
                    .padding(.horizontal, 16)
                }

                ForEach(store.state.groups, id: \.id) { group in
                    DuplicateGroupCardSwift(
                        group: group,
                        expanded: store.state.expandedGroupId == group.id,
                        preview: store.state.previews[group.id],
                        isMerging: store.state.isMerging,
                        strings: strings,
                        store: store
                    )
                    .padding(.horizontal, 16)
                }
            }
            .padding(.vertical, 8)
        }
    }

    /// شريط التراجع — رسالة + تراجع + إغلاق (نمط SilaUndoBar المشترك على أندرويد).
    private func undoBar(strings: DuplicatesReviewLocalizedStrings) -> some View {
        HStack(spacing: 8) {
            Text(strings.undoMessage)
                .font(.subheadline)
                .foregroundColor(.primary.inverse)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(strings.undo) {
                store.undoMerge()
            }
            .font(.subheadline.weight(.semibold))
            Button {
                store.dismissUndoBar()
            } label: {
                Image(systemName: "xmark").font(.footnote)
            }
            .accessibilityLabel(strings.backDescription)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(UIColor.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .padding(16)
    }

    private func errorText(strings: DuplicatesReviewLocalizedStrings) -> String {
        switch store.pendingErrorKey {
        case .mergeFailed: return strings.errorMerge
        case .undoFailed: return strings.errorUndo
        default: return strings.errorMerge
        }
    }
}

/// بطاقة مجموعة مكررات — رأس (شريحة الثقة + الدليل) + الأسماء، والتوسيع يكشف الأعضاء
/// ومعاينة الدمج وأفعال [دمج]/[ليست مكررات].
private struct DuplicateGroupCardSwift: View {

    let group: DuplicateGroup
    let expanded: Bool
    let preview: MergePlannerMergePlan?
    let isMerging: Bool
    let strings: DuplicatesReviewLocalizedStrings
    let store: DuplicatesReviewViewModelStore

    /// فاصل دمج الملاحظات الموثق (MergePlanner.NOTES_SEPARATOR) — ثابت بيانات لا نص واجهة.
    private static let notesSeparator = "\n—\n"

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                confidenceChip
                Text(strings.reasonLabel(kind: group.reasonKind, detail: group.reasonDetail))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }

            Text(group.contacts.map { $0.name.displayName }.joined(separator: " ↔ "))
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)

            if expanded {
                Divider()
                ForEach(group.contacts, id: \.id) { member in
                    memberRow(member)
                }
                if let preview {
                    previewSection(preview)
                }
                HStack {
                    Spacer()
                    Button(strings.notDuplicatesButton) {
                        store.markNotDuplicates(groupId: group.id)
                    }
                    .font(.footnote)
                    .disabled(isMerging)
                    Button(strings.mergeButton) {
                        store.mergeGroup(groupId: group.id)
                    }
                    .font(.footnote.weight(.bold))
                    .disabled(isMerging)
                }
            }
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .contentShape(Rectangle())
        .onTapGesture {
            store.toggleExpanded(groupId: group.id)
        }
    }

    private var confidenceChip: some View {
        let label: String
        let tint: Color
        switch group.confidence {
        case .high:
            label = strings.confidenceHigh
            tint = .accentColor
        case .medium:
            label = strings.confidenceMedium
            tint = .orange
        case .low:
            label = strings.confidenceLow
            tint = .secondary
        @unknown default:
            label = strings.confidenceLow
            tint = .secondary
        }
        return Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(tint.opacity(0.14), in: Capsule())
            .foregroundColor(tint)
    }

    private func memberRow(_ member: Contact) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.accentColor.opacity(0.15))
                .frame(width: 44, height: 44)
                .overlay(
                    Text(String(member.name.displayName.prefix(1)))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.accentColor)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(member.name.displayName).font(.subheadline).lineLimit(1)
                Text(strings.categoryTitle(member.category)).font(.caption).foregroundColor(.secondary)
            }
            Spacer(minLength: 8)
            if let phone = member.phones.first(where: { $0.isPrimary }) ?? member.phones.first {
                Button {
                    store.callMember(phone.e164)
                } label: {
                    Image(systemName: "phone.fill")
                        .frame(width: 44, height: 44)
                        .background(Color.accentColor.opacity(0.12), in: Circle())
                }
                .accessibilityLabel(strings.callMemberLabel)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            store.openMember(member.id)
        }
    }

    @ViewBuilder
    private func previewSection(_ plan: MergePlannerMergePlan) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(strings.previewTitle).font(.caption.weight(.semibold)).foregroundColor(.secondary)
            Text(plan.merged.name.displayName).font(.subheadline.weight(.semibold))
            Text(strings.previewSummary(
                phones: Int32(plan.merged.phones.count),
                emails: Int32(plan.merged.emails.count)
            ))
            .font(.caption)
            .foregroundColor(.secondary)
            if let notes = plan.merged.notes, notes.contains(Self.notesSeparator) {
                Text(strings.previewNotesMerged).font(.caption).foregroundColor(.secondary)
            }
            if plan.ringtoneConflict {
                Text(strings.ringtoneConflictNotice).font(.caption).foregroundColor(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// غلاف الشاشة بنمط ContactDetailsScreen — ملكية المخزن عبر @State تصمد لإعادة
/// بناء الجسم (store يُنشأ مرة واحدة لكل دخول المكدس).
struct DuplicatesReviewScreen: View {

    @State private var store: DuplicatesReviewViewModelStore
    private let language: AppLanguage
    private let onBack: () -> Void
    private let onOpenMember: (Int64) -> Void

    init(language: AppLanguage, onBack: @escaping () -> Void, onOpenMember: @escaping (Int64) -> Void) {
        _store = State(initialValue: DuplicatesReviewViewModelStore())
        self.language = language
        self.onBack = onBack
        self.onOpenMember = onOpenMember
    }

    var body: some View {
        DuplicatesReviewView(
            store: store,
            language: language,
            onBack: onBack,
            onOpenMember: onOpenMember
        )
    }
}
