import SharedLogic
import SwiftUI
import UIKit

// MARK: - ألوان دلالية (مرايا SilaColors — ألوان حالة أرحام بديناميكية فاتح/داكن)

/**
 * مرآة `SilaColors.forArhamStatus` (DESIGN_SYSTEM.md §1) — القيم السداسية نفسها
 * بدقة، كألوان ديناميكية تنعكس تلقائياً في الوضع الداكن (التضمين نشاطٌ واللون
 * حالة — T807-R2 كما في المواصفة).
 */
let arhamStatusOverdueColor = Color(red: 0.898, green: 0.282, blue: 0.302)   // #E5484D

let arhamStatusSoonColor = Color(red: 0.961, green: 0.647, blue: 0.141)      // #F5A524

let arhamStatusOkColor = Color(red: 0.188, green: 0.643, blue: 0.424)        // #30A46C

/// المرآة الدلالية لـ `MaterialTheme.colorScheme.secondary` (برتقالي الغروب — أرحام):
/// #FF6A3D فاتح / #FF8A63 داكن (SilaColors.SecondaryLight/SecondaryDark).
let arhamSecondaryColor = Color(uiColor: UIColor { trait in
    trait.userInterfaceStyle == .dark
        ? UIColor(red: 1.000, green: 0.541, blue: 0.388, alpha: 1)  // #FF8A63
        : UIColor(red: 1.000, green: 0.416, blue: 0.239, alpha: 1)  // #FF6A3D
})

/// حَكَم اللون الدلالي للحالة (نقية — قابلة للاختبار مباشرة).
func arhamStatusColor(_ status: ArhamStatus) -> Color {
    switch status {
    case .overdue: return arhamStatusOverdueColor
    case .soon: return arhamStatusSoonColor
    default: return arhamStatusOkColor
    }
}

/// مرآة `rank.toString().padStart(2, '0')` في `SilaArhamRankedRow` — «01، 02، 03».
func arhamRankFormatted(_ rank: Int) -> String {
    String(format: "%02d", rank)
}

/// مرآة شرط `state.screeningConsent == ScreeningConsentUi.NEEDED` — البطاقة بشرطها الوحيد.
func arhamShowsScreeningConsent(_ state: ArhamUiState) -> Bool {
    state.screeningConsent == .needed
}

// MARK: - سطر حدث التأكيد (الاحتياط الموثق — AC-7)

/**
 * مرآة `ConfirmationSheet` في Compose: تصنيف الاتجاه من الحقل المشترك (قيمة
 * `MISSED` حصرية للسجل القديم)، و«غير معروف» حين لا يعطي المنصة اتجاهاً،
 * ومدة الدقائق فقط من `durationSec` الموثوق — صفرُ احتمالٍ في العرض.
 */
func arhamConfirmationEventLine(for event: CallEvent, strings: ArhamLocalizedStrings) -> String {
    let direction: String
    if event.direction == .out {
        direction = strings.directionOutgoing
    } else if event.direction == .`in` {
        direction = strings.directionIncoming
    } else if event.direction == .missed {
        direction = strings.directionMissed
    } else {
        direction = strings.directionUnknown
    }
    // المدة: قسمة الثواني على 60 — كما `it / 60` في Compose (قسمة صحيحة).
    // الأقواس تُغلق سلسلة الاختيار حول القيمة المُعلَّبة — الـ `.map` على `Int` المفكوك
    // يرفضه المدقق (سجل CI 42183733461).
    let minutes = (event.durationSec?.intValue).map { $0 / 60 }
    return strings.confirmationEventLine(directionLabel: direction, durationMin: minutes)
}

// MARK: - التخطيط الحتمي للشجرة (مرآة computeTreeLayout — AC-13)

/// هندسة العقدة في الشجرة — موضع أقصى-اليسار بإحداثيات مطلقة (بلا RTL — مثل Compose).
struct ArhamTreeNodePlacement {
    let contactId: Int64
    let left: CGFloat
    let top: CGFloat
}

/// التخطيط الحتمي الصافي للشجرة — يُشارَك بين رسم الوصلات (Canvas) وتموضع
/// البطاقات بحيث لا يتباعد الرسم عن النقر (وظيفة نقية واحدة — خطة §P-T8-11).
struct ArhamTreeLayout {
    let placements: [Int64: ArhamTreeNodePlacement]
    let addButtons: [(layer: KinshipLayer, left: CGFloat, top: CGFloat)]
    let contentWidth: CGFloat
    let contentHeight: CGFloat
}

/// ثوابت هندسة الشجرة (مرايا NODE_WIDTH/… في ArhamTreeScreen.kt بالنقطة).
private let arhamNodeWidth: CGFloat = 132
private let arhamNodeHeight: CGFloat = 56
private let arhamHGap: CGFloat = 16
private let arhamVGap: CGFloat = 40
private let arhamAddSize: CGFloat = 40

/**
 * التخطيط الحتمي الصافي للشجرة (T8-11 · AC-13) — صفٌّ لكل توليدة بترتيب الإعلان،
 * «+» في نهاية كل صف (عقدة فارغة = ربط قائم بأرحام)، وارتفاع المحتوى بلا الفجوة
 * الأخيرة. نفس حساب Compose حرفياً (الاختبار يستهدف هذه الدقة).
 */
func computeArhamTreeLayout(_ tree: ArhamFamilyTree?) -> ArhamTreeLayout {
    var placements: [Int64: ArhamTreeNodePlacement] = [:]
    var addButtons: [(layer: KinshipLayer, left: CGFloat, top: CGFloat)] = []
    var top: CGFloat = 0
    var maxWidth: CGFloat = 0
    let generations = tree?.generations ?? []
    for generation in generations {
        var left: CGFloat = 0
        for node in generation.nodes {
            let contactId = node.contactId
            placements[contactId] = ArhamTreeNodePlacement(contactId: contactId, left: left, top: top)
            left += arhamNodeWidth + arhamHGap
        }
        addButtons.append((generation.layer, left, top + (arhamNodeHeight - arhamAddSize) / 2))
        left += arhamAddSize + arhamHGap
        maxWidth = max(maxWidth, left)
        top += arhamNodeHeight + arhamVGap
    }
    return ArhamTreeLayout(
        placements: placements,
        addButtons: addButtons,
        contentWidth: maxWidth,
        contentHeight: top > 0 ? top - arhamVGap : 0
    )
}

/// شجرة فارغة = لا نموذج أصلاً أو كل التوليدات بلا عقد (مرآة `isEmpty` في Compose).
func arhamTreeIsEmpty(_ tree: ArhamFamilyTree?) -> Bool {
    guard let tree else { return true }
    return tree.generations.allSatisfy { $0.nodes.isEmpty }
}

// MARK: - ثابت عرضي موثق

/// حدّ المحاولات الموثق (§N.4) — ثابت عرضي فقط (المصدر في السياسة)، مرآة Compose.
private let arhamEmergencyMaxAttempts = 10

// MARK: - الشاشة (Stateful رقيقة)

/**
 * شاشة أرحام على SwiftUI (T8-14 — §L.1 · AC-1/AC-2/AC-13).
 *
 * Stateful رقيقة: تعرض [ArhamUiState] المشترك وتُرسل [ArhamIntent] حصراً —
 * لا منطق أعمال ولا حساب حالة محلياً ولا لمس Room/المنصة إطلاقاً (نمط
 * `ContactsView`). آثار المنصة يجامعها [ArhamViewModelStore] حصرياً (قناة
 * أحادية المستهلك): الاتصال عبر الراوتر، والاحتفال حالة عابرة تُخفى بعد ثانيتين.
 *
 * الاتجاه والسمة وديناميكية الخط تُورَّث من البيئة (RTL/LTR من لغة الحالة
 * المشتركة عبر `ContentView`، والوضع الداكن من سمات النظام).
 */
struct ArhamView: View {

    var store: ArhamViewModelStore
    let language: AppLanguage

    /// إعادة سبر `canOpenURL("tel:")` عند العودة للواجهة (§L.4 — أزرار الاتصال
    /// تُخفى على iPad/بلا شريحة لصالح «نسخ الرقم»).
    @Environment(\.scenePhase) private var scenePhase
    @State private var dialProbeTick = 0

    var body: some View {
        // محلي للتوفير على مدقق الأنماط (§L.2 — نفس نمط ContactsView).
        let strings = ArhamLocalizedStrings(language: language)

        ZStack {
            if store.state.showTree {
                ArhamTreeSection(
                    tree: store.state.tree,
                    strings: strings,
                    store: store
                )
            } else {
                arhamContent(state: store.state, strings: strings)
            }

            if store.isCelebrating {
                arhamCelebrationOverlay(strings: strings)
            }
        }
        // مهمتان منفصلتان لا متتاليتان: observeState تنتظر تدفقاً لا نهائياً —
        // أي انتظار متتابع بعدها يبقى بلا أثر أبداً (نمط OnboardingView المجرب).
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { dialProbeTick += 1 }
        }
    }

    // MARK: المحتوى الرئيسي (بانتقال الشجرة هنا — مرآة ArhamContent)

    @ViewBuilder
    private func arhamContent(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                arhamPeriodHeader(state: state, strings: strings)

                arhamDashboardCard(state: state, strings: strings)

                if state.emergencySession != nil {
                    arhamEmergencyBanner(state: state, strings: strings)
                }

                if arhamShowsScreeningConsent(state) {
                    // AC-4 — على iOS الحالة HIDDEN دائماً (مصدر الموافقة يعيد null)
                    // فلا تُعرض البطاقة؛ الفرع محفوظ كمرآة عقد الواجهة المشترك.
                    arhamScreeningConsentCard(strings: strings)
                }

                if !state.pendingConfirmations.isEmpty {
                    arhamConfirmationsEntry(state: state, strings: strings)
                }

                if !state.occasions.isEmpty {
                    arhamOccasionsCard(occasions: state.occasions, strings: strings)
                }

                if state.items.isEmpty {
                    arhamEmptyState(strings: strings)
                } else {
                    arhamRankedList(items: state.items, strings: strings)
                }
            }
            .padding(16)
        }
        .confirmationDialog(
            arhamDialogTitle(state: state, strings: strings),
            isPresented: Binding(
                get: { state.showConfirmations && !state.pendingConfirmations.isEmpty },
                set: { if !$0 { store.toggleConfirmations() } }
            ),
            titleVisibility: .visible
        ) {
            arhamConfirmationAnswers(state: state, strings: strings)
        }
        .sheet(isPresented: Binding(
            get: { state.kinshipEditor != nil },
            set: { if !$0 { store.dismissKinshipEditor() } }
        )) {
            if let editor = state.kinshipEditor {
                ArhamKinshipEditorSheet(
                    editor: editor,
                    parentCandidates: arhamParentCandidates(state: state, editor: editor),
                    strings: strings,
                    store: store
                )
                // إعادة تهيئة الاختيارات المحلية لكل فتح (مرآة remember(editor.contactId)).
                .id(editor.contactId)
            }
        }
        .confirmationDialog(
            strings.treePickCandidate,
            isPresented: Binding(
                get: { state.generationAddPicker != nil },
                set: { if !$0 { store.dismissGenerationAddPicker() } }
            ),
            titleVisibility: .visible
        ) {
            if let picker = state.generationAddPicker {
                ForEach(arhamPickerCandidates(picker), id: \.contactId) { candidate in
                    Button(candidate.displayName) {
                        store.dismissGenerationAddPicker()
                        store.openKinshipEditor(contactId: candidate.contactId, restrictToLayer: picker.layer)
                    }
                }
                Button(strings.actionCancel, role: .cancel) {}
            }
        }
        .confirmationDialog(
            state.treeNodeSheet?.displayName ?? "",
            isPresented: Binding(
                get: { state.treeNodeSheet != nil },
                set: { if !$0 { store.dismissTreeNodeSheet() } }
            ),
            titleVisibility: .visible
        ) {
            if let node = state.treeNodeSheet {
                arhamTreeNodeActions(node: node, strings: strings)
            }
        }
    }

    /// عنوان ورقة التأكيد — يُبنى من الحدث الأول (الاحتياط الموثق للاتجاه/المدة).
    private func arhamDialogTitle(state: ArhamUiState, strings: ArhamLocalizedStrings) -> String {
        guard let event = state.pendingConfirmations.first else { return "" }
        return arhamConfirmationEventLine(for: event, strings: strings)
    }

    /// أزرار الإجابات الأربعة الموثقة — كل إجابة تحسم الحدث الأول عبر use case.
    @ViewBuilder
    private func arhamConfirmationAnswers(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        if let event = state.pendingConfirmations.first {
            Button("✅ \(strings.confirmContacted)") {
                store.resolveConfirmation(eventId: event.id, answer: .contacted)
            }
            Button("📵 \(strings.confirmNoAnswer)") {
                store.resolveConfirmation(eventId: event.id, answer: .noAnswer)
            }
            Button("🚫 \(strings.confirmNotRelated)") {
                store.resolveConfirmation(eventId: event.id, answer: .notRelated)
            }
            Button("⏰ \(strings.confirmLater)") {
                store.resolveConfirmation(eventId: event.id, answer: .later)
            }
            Button(strings.actionCancel, role: .cancel) {}
        }
    }

    /// مرشحو الربط الأبوي في محرر القرابة — أقارب الشجرة الآخرون (اسم العرض فقط).
    private func arhamParentCandidates(state: ArhamUiState, editor: KinshipEditorUi) -> [(contactId: Int64, displayName: String)] {
        let editorId = editor.contactId
        let generations = state.tree?.generations ?? []
        var candidates: [(contactId: Int64, displayName: String)] = []
        for generation in generations {
            for node in generation.nodes {
                let nodeId = node.contactId
                if nodeId != editorId {
                    candidates.append((nodeId, node.displayName))
                }
            }
        }
        return candidates
    }

    /// مرشحو منتقي «+» — أزواج Kotlin تُفكّ مرةً واحدة هنا (بقية الواجهة قيم صافية).
    private func arhamPickerCandidates(_ picker: GenerationAddPickerUi) -> [(contactId: Int64, displayName: String)] {
        var candidates: [(contactId: Int64, displayName: String)] = []
        for pair in picker.candidates {
            let contactId = (pair.first as? KotlinLong)?.int64Value ?? 0
            let displayName = (pair.second as? String) ?? ""
            candidates.append((contactId, displayName))
        }
        return candidates
    }

    /// أفعال ورقة عقدة الشجرة — الاتصال يظهر فقط بوجود رقم (نفس listOfNotNull).
    @ViewBuilder
    private func arhamTreeNodeActions(node: TreeNodeSheetUi, strings: ArhamLocalizedStrings) -> some View {
        if node.phone != nil {
            Button("📞 \(strings.treeNodeCall)") {
                store.recordCall(contactId: node.contactId, phone: node.phone)
            }
        }
        Button("✏️ \(strings.treeNodeEditKinship)") {
            store.dismissTreeNodeSheet()
            store.openKinshipEditor(contactId: node.contactId)
        }
        Button(strings.actionCancel, role: .cancel) {}
    }

    // MARK: رأس الفترة (حبّات الأسبوع/الشهر + فتح الشجرة)

    @ViewBuilder
    private func arhamPeriodHeader(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        let isWeek = state.period == .week
        HStack {
            Text(strings.screenTitle)
                .font(.headline)
            Spacer()
            HStack(spacing: 8) {
                arhamPeriodPill(label: strings.periodWeek, selected: isWeek) {
                    store.changePeriod(.week)
                }
                arhamPeriodPill(label: strings.periodMonth, selected: !isWeek) {
                    store.changePeriod(.month)
                }
                arhamPeriodPill(label: strings.openTree, selected: false) {
                    store.toggleTree()
                }
            }
        }
    }

    private func arhamPeriodPill(label: String, selected: Bool, onClick: @escaping () -> Void) -> some View {
        Button(action: onClick) {
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(selected ? Color.white : Color.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    selected ? Color.emeraldSila : Color(.secondarySystemBackground),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: بطاقة اللوحة (AC-2) — إحصاء + منصّة أعلى-3

    @ViewBuilder
    private func arhamDashboardCard(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        // محليات للتوفير على مدقق الأنماط (§L.2).
        let stats = state.stats
        let isWeek = state.period == .week

        VStack(spacing: 12) {
            Text(strings.podiumTitle(isWeek: isWeek))
                .font(.headline)

            HStack {
                arhamStatBadge(
                    label: strings.statCalls,
                    value: "\(stats.totalFamilyCalls)"
                )
                arhamStatBadge(
                    label: strings.statPaused,
                    value: "\(stats.pausedCount)"
                )
                arhamStatBadge(
                    label: strings.statConnectedLabel,
                    value: strings.statConnected(
                        contacted: Int(stats.contactedRelatives),
                        total: state.items.count
                    )
                )
                arhamStatBadge(
                    label: strings.statGoalLabel,
                    value: strings.statGoal(percent: Int(stats.goalCompletion * 100))
                )
            }

            ProgressView(value: Double(stats.goalCompletion))
                .progressViewStyle(.linear)
                .tint(.white)

            ForEach(stats.topThree, id: \.contactId) { item in
                arhamPodiumRow(
                    label: item.displayName,
                    status: item.status,
                    statusText: strings.statusText(item.status, daysSinceLastContact: item.daysSinceLastContact?.intValue)
                )
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(arhamSecondaryColor, in: RoundedRectangle(cornerRadius: 24))
    }

    private func arhamStatBadge(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.headline)
                .foregroundStyle(.white)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.9))
        }
    }

    /// صف المنصّة — شريط اللون دلالي من حالة الصف (لون حالة — T807-R2).
    private func arhamPodiumRow(label: String, status: ArhamStatus, statusText: String) -> some View {
        let statusColor = arhamStatusColor(status)
        return HStack(spacing: 12) {
            Capsule()
                .fill(statusColor)
                .frame(width: 4, height: 12)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(statusText)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.9))
                .lineLimit(1)
        }
    }

    // MARK: لافتة إيقاف الطوارئ (AC-14) — الحالة قراءة Room (SSOT) والإيقاف عبر use case

    @ViewBuilder
    private func arhamEmergencyBanner(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        if let session = state.emergencySession {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(strings.emergencyBannerTitle)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.red)
                    Text(strings.emergencyAttempts(
                        attempt: Int(session.attempts),
                        max: arhamEmergencyMaxAttempts
                    ))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    // AC-14: إفصاح حدود iOS داخل اللافتة حصراً — الاستحقاق الحرج غير مضمون
                    // (D7 fallback-first). حاشية صغيرة تتكيف مع RTL والداكن وأحجام الخطوط.
                    Text(strings.emergencyDisclosure)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button(action: { store.stopEmergency() }) {
                    Text(strings.emergencyStop)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.red, in: Capsule())
                }
                .accessibilityIdentifier("arham_emergency_stop")
            }
            .padding(16)
            .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 20))
        }
    }

    // MARK: بطاقة موافقة الفحص (AC-4 — السطح مستتر على iOS)

    @ViewBuilder
    private func arhamScreeningConsentCard(strings: ArhamLocalizedStrings) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings.screeningConsentTitle)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.secondary)
            Text(strings.screeningConsentBody)
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Button(action: { store.requestScreeningConsent() }) {
                    Text(strings.screeningConsentAction)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.emeraldSila, in: Capsule())
                }
                Button(action: { store.dismissScreeningConsent() }) {
                    Text(strings.screeningConsentDismiss)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: Capsule())
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))
    }

    // MARK: مدخل ورقة التأكيد (AC-7) — شارة العدد فقط؛ الحسم داخل الورقة

    @ViewBuilder
    private func arhamConfirmationsEntry(state: ArhamUiState, strings: ArhamLocalizedStrings) -> some View {
        Button(action: { store.toggleConfirmations() }) {
            Text(strings.confirmationsBadge(count: state.pendingConfirmations.count))
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Color.emeraldSila)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.emeraldSila.opacity(0.12), in: RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("arham_confirmations_entry")
    }

    // MARK: بطاقة المناسبات (O-5/O-8) — حقول خام والأسطر من النصوص المترجمة

    @ViewBuilder
    private func arhamOccasionsCard(occasions: [OccasionUi], strings: ArhamLocalizedStrings) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings.occasionsTitle)
                .font(.subheadline.weight(.bold))
            ForEach(occasions, id: \.contactId) { occasion in
                // محليات للتوفير على مدقق الأنماط (§L.2) — قراءةُ الخاصية مباشرةً وتسلسل
                // Combinators على `Optional` بلا إغلاقٍ داخلي ولا تهيئةٍ حتمية عبر `if`:
                // دمج `.map` بسلسلة اختيار داخل ViewBuilder أربك مدقق الأنماط حتى طبع
                // المستقبِل كـ KotlinBase (سجل CI 42183733461). `??` والقراءة المباشرة
                // هما النمط المثبت (onAppear — السطر 954).
                let personLine = occasion.relation
                    .map(strings.relationLabel)
                    .map { strings.occasionPerson($0, occasion.displayName) }
                    ?? occasion.displayName
                let dayLine = occasion.isToday
                    ? strings.occasionToday(personLine)
                    : strings.occasionInDays(Int(occasion.daysUntil), personLine)
                HStack {
                    Text(personLine)
                        .font(.subheadline)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Text(dayLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground).opacity(0.5), in: RoundedRectangle(cornerRadius: 20))
    }

    // MARK: الفراغ والقائمة المرتبة (AC-2)

    @ViewBuilder
    private func arhamEmptyState(strings: ArhamLocalizedStrings) -> some View {
        VStack(spacing: 8) {
            Text(strings.emptyTitle)
                .font(.headline)
            Text(strings.emptyBody)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func arhamRankedList(items: [ArhamItem], strings: ArhamLocalizedStrings) -> some View {
        let canDial = arhamCanOpenTel(dialProbeTick)
        VStack(spacing: 0) {
            ForEach(items, id: \.contactId) { item in
                arhamRankedRow(item: item, strings: strings, canDial: canDial)
            }
        }
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground).opacity(0.5), in: RoundedRectangle(cornerRadius: 24))
    }

    /// مرآة `SilaArhamRankedRow`: الترقيم «01»، الاسم، سطر الحالة الملون، وزر الاتصال.
    private func arhamRankedRow(item: ArhamItem, strings: ArhamLocalizedStrings, canDial: Bool) -> some View {
        let rank = Int(item.rank)
        let contactId = item.contactId
        let statusColor = arhamStatusColor(item.status)
        return HStack(spacing: 16) {
            Text(arhamRankFormatted(rank))
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(.secondary.opacity(0.65))
            VStack(alignment: .leading, spacing: 2) {
                Text(item.displayName)
                    .font(.subheadline.weight(.semibold))
                Text(strings.statusText(item.status, daysSinceLastContact: item.daysSinceLastContact?.intValue))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(statusColor)
            }
            Spacer(minLength: 0)
            if canDial {
                Button(action: { store.recordCall(contactId: contactId, phone: item.primaryPhone) }) {
                    Text("📞")
                        .frame(width: 48, height: 48)
                        .background(statusColor.opacity(0.16), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(strings.rowCallLabel)
            } else {
                // §L.4 (PLATFORM_PERMISSIONS §7): بلا هاتف (iPad/بلا شريحة) لا زر اتصال —
                // بديل النسخ يُبقي الرقم في يد المستخدم بلا وعد اتصال.
                Button(action: { UIPasteboard.general.string = item.primaryPhone }) {
                    Text(strings.copyNumber)
                        .font(.caption2)
                        .frame(width: 48, height: 48)
                        .background(statusColor.opacity(0.16), in: Circle())
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(strings.copyNumber)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// سبر القدرة الهاتفية — يُعاد عند كل تغيّر للمرحلة (الوسيطة تضمن القياس الطازج).
    private func arhamCanOpenTel(_ probeTick: Int) -> Bool {
        _ = probeTick
        guard let url = URL(string: "tel:") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    // MARK: غلاف الاحتفال (AC-12) — نظرة قصيرة ثم يختفي (السياسة تملك التفضيلات)

    @ViewBuilder
    private func arhamCelebrationOverlay(strings: ArhamLocalizedStrings) -> some View {
        Text(strings.celebrationTitle)
            .font(.subheadline.weight(.semibold))
            .padding(20)
            .background(Color.emeraldSila.opacity(0.18), in: RoundedRectangle(cornerRadius: 20))
            .padding(16)
    }
}

// MARK: - قسم الشجرة (مرآة ArhamTreeScreen — AC-13)

/// شاشة شجرة العائلة: تقريب/تحريك بإصبعين، «توسيطي» يعيد الكادر، وصلات الآباء
/// تُرسم على Canvas بإحداثيات التخطيط الحتمي نفسه، و«+» لكل توليدة، والنقر على
/// العقدة يفتح ورقتها (كل القرارات نِيَاتٌ حصراً — لا حساب مواضع مكرر).
struct ArhamTreeSection: View {

    let tree: ArhamFamilyTree?
    let strings: ArhamLocalizedStrings
    var store: ArhamViewModelStore

    /// حالة الكادر (توسيط/تكبير) — محلية العرض حصراً.
    @State private var scale: CGFloat = 1
    @State private var pan: CGSize = .zero

    var body: some View {
        let isEmpty = arhamTreeIsEmpty(tree)

        VStack(spacing: 0) {
            HStack {
                arhamTreePill(label: strings.closeTree) {
                    store.toggleTree()
                }
                Text(strings.treeTitle)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                arhamTreePill(label: strings.treeCenterOnMe) {
                    // «توسيطي»: إعادة الكادر إلى الوضع الأول — أعلى الشجرة.
                    scale = 1
                    pan = .zero
                }
            }
            .padding(16)

            if isEmpty {
                Spacer()
                Text(strings.treeEmpty)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(32)
                Spacer()
            } else {
                arhamTreeCanvas()
            }
        }
    }

    /// الرسم والتموضع من التخطيط الحتمي المشترك نفسه — لا انحراف بينهما.
    @ViewBuilder
    private func arhamTreeCanvas() -> some View {
        let layout = computeArhamTreeLayout(tree)
        let nodes = arhamNodesById()

        ScrollView([.horizontal, .vertical]) {
            ZStack(alignment: .topLeading) {
                Canvas { context, _ in
                    drawTreeEdges(context: &context, layout: layout)
                }
                .frame(width: layout.contentWidth, height: layout.contentHeight)

                ForEach(Array(layout.placements.values), id: \.contactId) { placement in
                    if let node = nodes[placement.contactId] {
                        arhamTreeNode(node: node)
                            .offset(x: placement.left, y: placement.top)
                    }
                }

                ForEach(Array(layout.addButtons.enumerated()), id: \.offset) { _, button in
                    arhamTreeAddButton(layer: button.layer)
                        .offset(x: button.left, y: button.top)
                }
            }
            .frame(width: layout.contentWidth, height: layout.contentHeight, alignment: .topLeading)
            .scaleEffect(scale)
            .offset(pan)
            .gesture(
                MagnifyGesture()
                    .onChanged { value in
                        let next = scale * value.magnification
                        scale = min(max(next, 0.5), 2.5)
                    }
                    .simultaneously(with: DragGesture()
                        .onChanged { value in
                            pan = CGSize(width: pan.width + value.translation.width, height: pan.height + value.translation.height)
                        }
                    )
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    /// فهرس العقد بالمعرّف — يشاركه الرسم والتموضع (مرآة firstOrNull في Compose).
    private func arhamNodesById() -> [Int64: ArhamTreeNode] {
        var nodes: [Int64: ArhamTreeNode] = [:]
        let generations = tree?.generations ?? []
        for generation in generations {
            for node in generation.nodes {
                nodes[node.contactId] = node
            }
        }
        return nodes
    }

    /// الوصلات أولاً — تحت البطاقات (ابن←والد: قاع الوالد لقمة الابن).
    private func drawTreeEdges(context: inout GraphicsContext, layout: ArhamTreeLayout) {
        let edges = tree?.edges ?? []
        for edge in edges {
            let childId = edge.childContactId
            let parentId = edge.parentContactId
            guard let parent = layout.placements[parentId], let child = layout.placements[childId] else { continue }
            let startX = parent.left + arhamNodeWidth / 2
            let startY = parent.top + arhamNodeHeight
            let endX = child.left + arhamNodeWidth / 2
            let endY = child.top
            var path = Path()
            path.move(to: CGPoint(x: startX, y: startY))
            path.addLine(to: CGPoint(x: endX, y: endY))
            context.stroke(path, with: .color(Color(.systemGray3)), lineWidth: 2)
        }
    }

    /// بطاقة عقدة الشجرة — حلقة الحالة دلالية، والنقر يفتح ورقة العقدة.
    private func arhamTreeNode(node: ArhamTreeNode) -> some View {
        let contactId = node.contactId
        let statusColor = arhamStatusColor(node.status)
        return VStack(spacing: 2) {
            Text(node.displayName)
                .font(.subheadline)
                .lineLimit(1)
            Text(strings.relationLabel(node.relation))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .frame(width: arhamNodeWidth, height: arhamNodeHeight, alignment: .center)
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(statusColor, lineWidth: 2)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture {
            store.openTreeNodeSheet(contactId: contactId)
        }
    }

    /// «+» في نهاية كل صف توليدة (AC-13: عقدة فارغة = ربط قائم بأرحام).
    private func arhamTreeAddButton(layer: KinshipLayer) -> some View {
        Button(action: { store.openGenerationAdd(layer) }) {
            Text(strings.treeAdd)
                .font(.headline)
                .foregroundStyle(.secondary)
                .frame(width: arhamAddSize, height: arhamAddSize)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
    }

    /// حبة رأس الشجرة (رجوع/توسيط) — هدف لمس 48 دن موثق.
    private func arhamTreePill(label: String, onClick: @escaping () -> Void) -> some View {
        Button(action: onClick) {
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemBackground), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - ورقة تحرير القرابة (مرآة KinshipEditorSheet — AC-1)

/**
 * ورقة تحرير القرابة على SwiftUI: الخطوة 1 القرابة (مصفاة بالطبقة عند «+» صف
 * التوليدة)، الخطوة 2 الوتيرة مع **الاقتراح الموثق** (الافتراضيات من
 * `UpdateKinshipUseCase.defaultFrequencyFor` حصراً — لا منطق أعمال محلي)، ثم
 * الربط بقريبٍ آخر (حافة الشجرة AC-13) والإيقاف المؤقت (30 يوماً — الخيار
 * الموثق الوحيد). الحفظ نيةُ [ArhamIntentSaveKinship] حصراً.
 */
struct ArhamKinshipEditorSheet: View {

    let editor: KinshipEditorUi
    let parentCandidates: [(contactId: Int64, displayName: String)]
    let strings: ArhamLocalizedStrings
    var store: ArhamViewModelStore

    /// اختيارات محلية تُهيَّأ من المحرّر (مرآة remember(editor.contactId) — إعادة
    /// التهيئة عبر .id في موضع الاستدعاء).
    @State private var selectedRelation: Relation?
    @State private var selectedFrequency: ArhamFrequency?
    @State private var selectedParentId: Int64?
    @State private var pauseOneMonth = false
    @State private var showParentPicker = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(strings.kinshipTitle(editor.contactName))
                        .font(.headline)

                    Text(strings.kinshipStepRelation)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(arhamAllowedRelations(), id: \.self) { relation in
                        arhamSelectionRow(
                            label: strings.relationLabel(relation),
                            selected: selectedRelation == relation
                        ) {
                            selectedRelation = relation
                            // اقتراح الوتيرة الموثق للقرابة الجديدة — لا إجبار (AC-1).
                            let current = selectedFrequency
                            if current == nil || current == editor.proposedFrequency {
                                // الاقتراح عبر الرفيق المتداخل `UpdateKinshipUseCase.Companion.shared`
                            // — تصدير K/N يعيد تسمية الرفيق بنوعٍ متداخل (نمط KotlinVersion.Companion.shared)
                            // — الاسم المسطّح UpdateKinshipUseCaseCompanion غير موجود في نطاق Swift
                            // (سجل CI 42183733461).
                            selectedFrequency = UpdateKinshipUseCase.Companion.shared.defaultFrequencyFor(relation: relation)
                            }
                        }
                    }

                    Text(strings.kinshipStepFrequency)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(arhamFrequencies(), id: \.self) { frequency in
                        arhamSelectionRow(
                            label: strings.frequencyLabel(frequency),
                            selected: selectedFrequency == frequency
                        ) {
                            selectedFrequency = frequency
                        }
                    }
                    if selectedFrequency == nil {
                        Text(strings.kinshipFrequencyRequired)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    arhamSelectionRow(
                        label: strings.kinshipParentLabel,
                        selected: false,
                        trailing: arhamSelectedParentName()
                    ) {
                        showParentPicker = true
                    }

                    arhamSelectionRow(
                        label: strings.kinshipPauseLabel,
                        selected: pauseOneMonth,
                        trailing: pauseOneMonth ? strings.kinshipPauseOneMonth : nil
                    ) {
                        pauseOneMonth.toggle()
                    }

                    arhamSaveButton()

                    Button(strings.actionCancel) {
                        store.dismissKinshipEditor()
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
                .padding(20)
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            // التهيئة من المحرّر حصراً (نفس التعبيرات الافتراضية في Compose).
            selectedRelation = editor.preselectedRelation ?? editor.currentRelation
            selectedFrequency = editor.currentFrequency ?? editor.proposedFrequency
            selectedParentId = editor.currentParentContactId?.int64Value
        }
        .confirmationDialog(
            strings.kinshipParentLabel,
            isPresented: $showParentPicker,
            titleVisibility: .visible
        ) {
            Button(strings.kinshipParentNone) {
                selectedParentId = nil
                showParentPicker = false
            }
            ForEach(parentCandidates, id: \.contactId) { candidate in
                Button("👤 \(candidate.displayName)") {
                    selectedParentId = candidate.contactId
                    showParentPicker = false
                }
            }
            Button(strings.actionCancel, role: .cancel) {}
        }
    }

    /// القرابات المسموحة — مصفاة بالطبقة عند الإضافة من «+» (restrictToLayer).
    private func arhamAllowedRelations() -> [Relation] {
        let all: [Relation] = [
            .grandfather, .grandmother, .father, .mother, .brother, .sister, .son,
            .daughter, .spouse, .unclePaternal, .auntPaternal, .uncleMaternal,
            .auntMaternal, .cousinPaternal, .cousinMaternal, .inLaw, .closeFriend, .custom,
        ]
        guard let layer = editor.restrictToLayer else { return all }
        return all.filter { relation in
            // طبقة القرابة من النموذج المشترك حصراً — لا إعادة اختراع (AC-13).
            relation.layer == layer
        }
    }

    /// مرآة `ArhamFrequency.entries` — قائمة ثابتة بلا منطق.
    private func arhamFrequencies() -> [ArhamFrequency] {
        [.daily, .weekly, .biweekly, .monthly, .occasions]
    }

    private func arhamSelectedParentName() -> String? {
        guard let parentId = selectedParentId else { return strings.kinshipParentNone }
        return parentCandidates.first { $0.contactId == parentId }?.displayName
    }

    /// صف اختيار موحد — حبة منتقاة بلون الطبقة الدلالية (داكن/فاتح تلقائياً).
    private func arhamSelectionRow(label: String, selected: Bool, trailing: String? = nil, onClick: @escaping () -> Void) -> some View {
        Button(action: onClick) {
            HStack(spacing: 8) {
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(selected ? Color.white : Color.secondary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if let trailing {
                    Text(trailing)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                selected ? Color.emeraldSila : Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 24)
            )
        }
        .buttonStyle(.plain)
    }

    /// الحفظ — مفعلٌ فقط بقرابة ووتيرة (الإلزام الموثق)؛ النية تحمل كل الحقول.
    @ViewBuilder
    private func arhamSaveButton() -> some View {
        let canSave = selectedRelation != nil && selectedFrequency != nil
        Button(action: { arhamSave() }) {
            Text(strings.kinshipSave)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(canSave ? Color.white : Color.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    canSave ? Color.emeraldSila : Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 24)
                )
        }
        .buttonStyle(.plain)
        .disabled(!canSave)
    }

    private func arhamSave() {
        guard let relation = selectedRelation else { return }
        store.saveKinship(
            contactId: editor.contactId,
            relation: relation,
            frequency: selectedFrequency,
            parentContactId: selectedParentId,
            pauseForDays: pauseOneMonth ? 30 : nil
        )
    }
}
