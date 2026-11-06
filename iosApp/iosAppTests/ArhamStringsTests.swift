import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبار اكتمال مفاتيح موارد الترجمة لشاشات أرحام (T8-14 — §L.9): كل مفتاح
 * يستهلكه [ArhamLocalizedStrings] و`ArhamView` يجب أن يكون موجوداً فعلاً في
 * `ar.lproj` و`en.lproj` داخل حزمة التطبيق المضيفة — لا نصوص ثابتة (AGENTS.md)
 * ولا مفاتيح مفقودة تنزل بصمت للمفتاح (نمط ContactsStringsTests).
 */
final class ArhamStringsTests: XCTestCase {

    /// المفاتيح المستهلكة فعلياً من ArhamStrings.swift وArhamView.swift —
    /// مرآة مفاتيح Compose (sharedUI/composeResources arham_*/layer_*) حرفياً.
    private let requiredKeys = [
        // العنوان والفترات
        "arham_screen_title", "arham_period_week", "arham_period_month",
        "arham_open_tree", "arham_close_tree",
        // اللوحة
        "arham_podium_week", "arham_podium_month", "arham_stats_calls",
        "arham_stat_paused", "arham_stat_connected_label", "arham_stat_connected_value",
        "arham_stat_goal_value", "arham_stats_target",
        // الفراغ والاحتفال
        "arham_empty_title", "arham_empty_body", "arham_celebration_title",
        // حالات الصفوف
        "arham_row_overdue", "arham_row_overdue_today", "arham_row_soon",
        "arham_row_soon_today", "arham_row_ok",
        // التأكيدات والاتجاهات
        "arham_confirmations_badge", "arham_confirmation_event_duration",
        "arham_confirmation_event_plain", "arham_confirm_contacted",
        "arham_confirm_no_answer", "arham_confirm_not_related", "arham_confirm_later",
        "arham_direction_outgoing", "arham_direction_incoming",
        "arham_direction_missed", "arham_direction_unknown",
        // المناسبات
        "arham_occasions_title", "arham_occasion_person", "arham_occasion_today",
        "arham_occasion_in_days",
        // القرابة
        "arham_kinship_title", "arham_kinship_step_relation", "arham_kinship_step_frequency",
        "arham_kinship_parent_label", "arham_kinship_parent_none", "arham_kinship_pause_label",
        "arham_kinship_pause_month", "arham_kinship_frequency_required",
        "arham_frequency_daily", "arham_frequency_weekly", "arham_frequency_biweekly",
        "arham_frequency_monthly", "arham_frequency_occasions",
        // القرابات والطبقات
        "arham_relation_grandfather", "arham_relation_grandmother",
        "arham_relation_father", "arham_relation_mother", "arham_relation_brother",
        "arham_relation_sister", "arham_relation_son", "arham_relation_daughter",
        "arham_relation_spouse", "arham_relation_uncle_paternal", "arham_relation_aunt_paternal",
        "arham_relation_uncle_maternal", "arham_relation_aunt_maternal",
        "arham_relation_cousin_paternal", "arham_relation_cousin_maternal",
        "arham_relation_in_law", "arham_relation_close_friend", "arham_relation_custom",
        "layer_ancestor", "layer_self_branch", "layer_collateral_paternal",
        "layer_collateral_maternal", "layer_spouse_side", "layer_other",
        // الشجرة
        "arham_tree_title", "arham_tree_center", "arham_tree_empty",
        "arham_tree_add", "arham_tree_pick_candidate", "arham_tree_node_edit_kinship",
        // الطوارئ (اللافتة — البداية من مضيف المنصة)
        "arham_emergency_banner_title", "arham_emergency_stop", "arham_emergency_attempts",
        // موافقة الفحص (السطح مستتر على iOS لكن الفرع محفوظ في الواجهة المشتركة)
        "arham_screening_consent_title", "arham_screening_consent_body",
        "arham_screening_consent_action", "arham_screening_consent_dismiss",
        // أفعال مشتركة + بديل النسخ (§L.4)
        "action_cancel", "action_call", "action_save", "arham_action_copy_number",
    ]

    private var appBundle: Bundle { Bundle.main } // الاختبار مستضاف داخل التطبيق

    private func stringsTable(for language: String) throws -> [String: String] {
        let lprojPath = try XCTUnwrap(
            appBundle.path(forResource: language, ofType: "lproj"),
            "Missing \(language).lproj in the hosted app bundle"
        )
        return try XCTUnwrap(
            NSDictionary(contentsOfFile: lprojPath + "/Localizable.strings") as? [String: String],
            "Unreadable Localizable.strings for \(language)"
        )
    }

    func testAllArhamKeysExistInBothLocalizations() throws {
        for language in ["ar", "en"] {
            let table = try stringsTable(for: language)
            for key in requiredKeys {
                let value = table[key]
                XCTAssertNotNil(value, "Missing key \(key) in \(language).lproj/Localizable.strings")
                XCTAssertFalse(value?.isEmpty ?? true, "Empty value for \(key) in \(language)")
            }
        }
    }

    func testArhamKeysHaveParityAcrossLocalizations() throws {
        // توازي المجموعتين نفسها — لا مفتاح عربي بلا مقابل إنجليزي والعكس.
        let ar = try stringsTable(for: "ar")
        let en = try stringsTable(for: "en")
        let arArham = Set(ar.keys.filter { $0.hasPrefix("arham_") || $0.hasPrefix("layer_") })
        let enArham = Set(en.keys.filter { $0.hasPrefix("arham_") || $0.hasPrefix("layer_") })
        XCTAssertEqual(arArham, enArham, "arham/layer key sets must be identical across locales")
    }

    func testArhamCountTemplatesUsePositionalSpecifiers() throws {
        // قوالب العدد موضعية (%1$ld/%2$ld) وصيغة %@ للنصوص — صيغة String(format:) في Swift.
        for language in ["ar", "en"] {
            let table = try stringsTable(for: language)
            XCTAssertTrue(table["arham_stat_connected_value"]?.contains("%1$ld") ?? false)
            XCTAssertTrue(table["arham_stat_connected_value"]?.contains("%2$ld") ?? false)
            XCTAssertTrue(table["arham_emergency_attempts"]?.contains("%1$ld") ?? false)
            XCTAssertTrue(table["arham_confirmation_event_plain"]?.contains("%1$@") ?? false)
            XCTAssertTrue(table["arham_occasion_in_days"]?.contains("%1$ld") ?? false)
            XCTAssertTrue(table["arham_occasion_in_days"]?.contains("%2$@") ?? false)
            // النسبة الإنجليزية تهرب النسبة المئوية الحرفية — عربياً محرف ٪ العربي بلا هروب.
            if language == "en" {
                XCTAssertTrue(table["arham_stat_goal_value"]?.contains("%%") ?? false)
            }
        }
    }

    @MainActor
    func testLocalizedStringsResolveThroughSharedLanguage() {
        // اللغة من الحالة المشتركة نفسها — نفس مسار الإنتاج بالتمام.
        let arabic = ArhamLocalizedStrings(language: AppLanguage.arabic)
        XCTAssertNotEqual(arabic.screenTitle, "arham_screen_title")
        XCTAssertEqual(arabic.screenTitle, "أرحام")
        XCTAssertEqual(arabic.periodWeek, "أسبوع")
        XCTAssertEqual(arabic.statConnected(contacted: 3, total: 8), "3 من 8")
        XCTAssertEqual(arabic.emergencyAttempts(attempt: 2, max: 10), "المحاولة 2 من 10")
        XCTAssertEqual(arabic.statusText(.ok, daysSinceLastContact: nil), "تمّ التواصل مؤخراً")
        XCTAssertEqual(arabic.statusText(.overdue, daysSinceLastContact: 0), "يحتاج اتصال — اليوم")
        // اتجاه تركيب المناسبة محوَّل بالمعاملين — N2: يغطي الاختبار صيغتي اللغتين.
        XCTAssertEqual(arabic.occasionPerson("عمّتك", "سعاد"), "عمّتك سعاد")
        XCTAssertEqual(english.occasionPerson("your aunt (paternal)", "Suad"), "Suad (your aunt (paternal))")
        XCTAssertEqual(arabic.relationLabel(.auntPaternal), "عمّتك")
        XCTAssertEqual(arabic.layerLabel(.spouseSide), "جهة الزوج")

        let english = ArhamLocalizedStrings(language: AppLanguage.english)
        XCTAssertEqual(english.screenTitle, "Arham")
        XCTAssertEqual(english.statConnected(contacted: 3, total: 8), "3 of 8")
        XCTAssertEqual(english.statGoal(percent: 75), "75%")
        XCTAssertEqual(english.confirmationEventLine(directionLabel: "Outgoing", durationMin: 2),
                       "Outgoing call · 2 min — did you connect?")
        XCTAssertEqual(english.confirmationEventLine(directionLabel: "Missed", durationMin: nil),
                       "Missed call — did you connect?")
        XCTAssertEqual(english.statusText(.soon, daysSinceLastContact: 4), "Due soon (4 d)")
        XCTAssertEqual(english.relationLabel(.father), "your father")
        XCTAssertEqual(english.copyNumber, "Copy number")
    }
}
