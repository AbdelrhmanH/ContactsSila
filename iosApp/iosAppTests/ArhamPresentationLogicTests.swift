import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبارات منطق العرض النقي لشاشات أرحام (T8-14 — §L.10): التخطيط الحتمي للشجرة،
 * احتياطات سطر التأكيد (AC-7)، فروع القدرات (موافقة الفحص/ألوان الحالة)، وفكّ
 * لقطة الودجت (حد الوصول D6) — مرآة ContactsPresentationLogicTests (نقية حصراً،
 * بلا Koin ولا قاعدة بيانات).
 *
 * ملاحظة مصادر الاختبار: المُنشئات ذات المعاملات الافتراضية لا تُصدَّر لـ Swift —
 * كل القيم صريحة أدناه وتطابق افتراضيات Kotlin حرفياً.
 */
final class ArhamPresentationLogicTests: XCTestCase {

    // MARK: - مصانع الاختبار

    private func node(
        _ id: Int64,
        relation: Relation = .brother,
        layer: KinshipLayer = .selfBranch,
        status: ArhamStatus = .ok,
        days: Int? = 3
    ) -> ArhamTreeNode {
        // الأرقام غير القابلة للفراغ تجسر أصلية (Int64/Double) والقابلة تُعلَّب مرة واحدة (§L.2).
        ArhamTreeNode(
            contactId: id,
            displayName: "عقدة \(id)",
            photoUri: nil,
            relation: relation,
            layer: layer,
            parentContactId: nil,
            urgency: 1.0,
            status: status,
            daysSinceLastContact: days.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) }
        )
    }

    private func event(
        direction: CallDirection?,
        durationSec: Int?
    ) -> CallEvent {
        CallEvent(
            id: "evt-1",
            primarySource: CallSource.manual,
            evidenceSources: [CallSource.manual],
            timestamp: 1_700_000_000_000,
            remoteNumber: nil,
            contactId: nil,
            direction: direction,
            startedAt: nil,
            connectedAt: nil,
            endedAt: nil,
            durationSec: durationSec.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) },
            outcome: CallOutcome.unknown,
            association: CallAssociation.unassociated,
            confirmation: CallConfirmation.pending,
            confidence: CallConfidence.low,
            dedupeKey: nil,
            simSlot: nil
        )
    }

    private var english: ArhamLocalizedStrings { ArhamLocalizedStrings(language: AppLanguage.english) }

    // MARK: - التخطيط الحتمي للشجرة (AC-13 — مرآة computeTreeLayout)

    private var twoGenerationTree: ArhamFamilyTree {
        ArhamFamilyTree(
            generations: [
                ArhamTreeGeneration(layer: .ancestor, nodes: [node(10, relation: .father, layer: .ancestor), node(11, relation: .mother, layer: .ancestor)]),
                ArhamTreeGeneration(layer: .selfBranch, nodes: [node(20)]),
            ],
            edges: [ArhamTreeEdge(childContactId: 20, parentContactId: 10)]
        )
    }

    func testTreeLayoutPlacesNodesRowByRowWithDeterministicOffsets() {
        let layout = computeArhamTreeLayout(twoGenerationTree)

        // الصف الأول عند (0،0) والثاني بإزاحة NODE_WIDTH+H_GAP (132+16=148)،
        // والصف التالي V_GAP+NODE_HEIGHT (40+56=96) — نفس حساب Compose حرفياً.
        XCTAssertEqual(layout.placements[10]?.left ?? -1, 0, accuracy: 0.001)
        XCTAssertEqual(layout.placements[11]?.left ?? -1, 148, accuracy: 0.001)
        XCTAssertEqual(layout.placements[20]?.top ?? -1, 96, accuracy: 0.001)
        XCTAssertEqual(layout.placements.count, 3)
    }

    func testTreeLayoutAddsButtonAtRowEndVerticallyCentered() {
        let layout = computeArhamTreeLayout(twoGenerationTree)

        // «+» بعد آخر عقدة في الصف ومُوسّطة رأسياً: top + (56-40)/2 = +8.
        XCTAssertEqual(layout.addButtons.count, 2)
        XCTAssertEqual(layout.addButtons[0].layer, .ancestor)
        XCTAssertEqual(layout.addButtons[0].left, 296, accuracy: 0.001)
        XCTAssertEqual(layout.addButtons[0].top, 8, accuracy: 0.001)
        XCTAssertEqual(layout.addButtons[1].left, 148, accuracy: 0.001)
        XCTAssertEqual(layout.addButtons[1].top, 104, accuracy: 0.001)
    }

    func testTreeLayoutContentSizeExcludesTrailingVerticalGap() {
        let layout = computeArhamTreeLayout(twoGenerationTree)

        // العرض أوسع صف (352) والارتفاع بلا الفجوة الأخيرة (96+56+40-40=152).
        XCTAssertEqual(layout.contentWidth, 352, accuracy: 0.001)
        XCTAssertEqual(layout.contentHeight, 152, accuracy: 0.001)
    }

    func testTreeLayoutOfNilAndEmptyTreesIsEmpty() {
        let nilLayout = computeArhamTreeLayout(nil)
        XCTAssertTrue(nilLayout.placements.isEmpty)
        XCTAssertTrue(nilLayout.addButtons.isEmpty)
        XCTAssertEqual(nilLayout.contentHeight, 0)

        let emptyTree = ArhamFamilyTree(
            generations: [ArhamTreeGeneration(layer: .other, nodes: [])],
            edges: []
        )
        let emptyLayout = computeArhamTreeLayout(emptyTree)
        XCTAssertTrue(emptyLayout.placements.isEmpty)
        XCTAssertTrue(arhamTreeIsEmpty(emptyTree))
        XCTAssertTrue(arhamTreeIsEmpty(nil))
        XCTAssertFalse(arhamTreeIsEmpty(twoGenerationTree))
    }

    // MARK: - سطر حدث التأكيد (AC-7 — احتياطات الاتجاه والمدة)

    func testConfirmationLineUsesMinutesFromDocumentedDuration() {
        // 130 ثانية → دقيقتان (قسمة صحيحة كما `it / 60` في Compose).
        let line = arhamConfirmationEventLine(for: event(direction: .out, durationSec: 130), strings: english)
        XCTAssertEqual(line, "Outgoing call · 2 min — did you connect?")
    }

    func testConfirmationLineFallsBackToPlainWithoutDuration() {
        let line = arhamConfirmationEventLine(for: event(direction: .missed, durationSec: nil), strings: english)
        XCTAssertEqual(line, "Missed call — did you connect?")
    }

    func testConfirmationLineFallsBackToUnknownDirection() {
        // null = المنصة لم تعطِ اتجاهاً — لا يُستنتج ولا يُخترع (عقد ADR-0006).
        let line = arhamConfirmationEventLine(for: event(direction: nil, durationSec: nil), strings: english)
        XCTAssertEqual(line, "Unknown direction call — did you connect?")
    }

    func testConfirmationLineCoversBacktickIncomingCase() {
        // الحالة `in` محارفة بالفواصل العليا (SKIE/K-N — §L.2).
        let line = arhamConfirmationEventLine(for: event(direction: .`in`, durationSec: 60), strings: english)
        XCTAssertEqual(line, "Incoming call · 1 min — did you connect?")
    }

    // MARK: - فروع القدرات (§L.10)

    func testScreeningConsentCardOnlyForNeededState() {
        // على iOS مصدر الموافقة يعيد null → الحالة HIDDEN دائماً؛ الفرع محفوظ
        // كمرآة للعقد المشتركة (لا بطاقة وهمية على المنصة التي لا دور فرز فيها).
        func state(_ consent: ScreeningConsentUi) -> ArhamUiState {
            ArhamUiState(
                period: .week, items: [], stats: ArhamDashboardStats(
                    period: .week, topThree: [], totalFamilyCalls: 0, longestCallSec: 0,
                    contactedRelatives: 0, goalCompletion: 0, pausedCount: 0
                ),
                occasions: [], showTree: false, tree: nil, pendingConfirmations: [],
                showConfirmations: false, kinshipEditor: nil, treeNodeSheet: nil,
                generationAddPicker: nil, emergencySession: nil, screeningConsent: consent
            )
        }
        XCTAssertTrue(arhamShowsScreeningConsent(state(.needed)))
        XCTAssertFalse(arhamShowsScreeningConsent(state(.hidden)))
        XCTAssertFalse(arhamShowsScreeningConsent(state(.granted)))
    }

    func testStatusColorsAreSemanticMirrorsOfSilaColors() {
        // الألوان الثلاثة الدلالية نفسها (DESIGN_SYSTEM.md §1) — التضمين نشاط
        // واللون حالة (T807-R2).
        XCTAssertEqual(arhamStatusColor(.overdue), arhamStatusOverdueColor)
        XCTAssertEqual(arhamStatusColor(.soon), arhamStatusSoonColor)
        XCTAssertEqual(arhamStatusColor(.ok), arhamStatusOkColor)
    }

    func testRankFormattingPadsToTwoDigits() {
        // مرآة padStart(2, '0') — «01، 02، 03» بخط Light (DESIGN_SYSTEM.md §4.8).
        XCTAssertEqual(arhamRankFormatted(1), "01")
        XCTAssertEqual(arhamRankFormatted(23), "23")
    }

    // MARK: - فكّ لقطة الودجت (D6 — حد الوصول الوحيد للإضافة)

    /// مرآة فكّ الاختبار لـ `ArhamWidgetSnapshot` في `SnapshotReader.swift` (هدف الودجت
    /// — لا يُرى من هدف الاختبارات): طابق حقليةً حرفياً مع `IosArhamWidgetSnapshotDto`.
    /// أي انحراف بين JSON النشر وفكّ الودجت يُكتشف هنا (نفس قيود `Codable`).
    private struct ArhamWidgetSnapshot: Codable {
        struct Entry: Codable {
            let contactId: Int64
            let displayName: String
            let relation: String
            let daysSinceLastContact: Int?
            let status: String
        }

        let overdueCount: Int
        let entries: [Entry]
        let totalFamilyCalls: Int
        let longestCallSec: Int
        let goalCompletionFraction: Double
        let generatedAtMillis: Int64
    }

    func testSnapshotDecodesAppPublishedJsonWithNullDays() throws {
        // المرآة الحقلية لـ IosArhamWidgetSnapshotDto — أي انحراف يُكتشف هنا قبل
        // أن يصل إلى الودجت (fail-safe: nil يبقى nil في العرض).
        let json = """
        {"overdueCount":2,
         "entries":[{"contactId":7,"displayName":"سعاد","relation":"AUNT_PATERNAL",
                     "daysSinceLastContact":null,"status":"OVERDUE"}],
         "totalFamilyCalls":12,"longestCallSec":300,
         "goalCompletionFraction":0.75,"generatedAtMillis":1700000000000}
        """.data(using: .utf8)!

        let snapshot = try JSONDecoder().decode(ArhamWidgetSnapshot.self, from: json)

        XCTAssertEqual(snapshot.overdueCount, 2)
        XCTAssertEqual(snapshot.entries.count, 1)
        XCTAssertEqual(snapshot.entries.first?.contactId, 7)
        XCTAssertEqual(snapshot.entries.first?.displayName, "سعاد")
        XCTAssertEqual(snapshot.entries.first?.relation, "AUNT_PATERNAL")
        XCTAssertNil(snapshot.entries.first?.daysSinceLastContact, "null = لا دليل — لا استنتاج (ADR-0006)")
        XCTAssertEqual(snapshot.entries.first?.status, "OVERDUE")
        XCTAssertEqual(snapshot.totalFamilyCalls, 12)
        XCTAssertEqual(snapshot.longestCallSec, 300)
        XCTAssertEqual(snapshot.goalCompletionFraction, 0.75, accuracy: 0.0001)
        XCTAssertEqual(snapshot.generatedAtMillis, 1_700_000_000_000)
    }
}
