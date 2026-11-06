import Foundation
import SharedLogic

/**
 * نصوص شاشات أرحام لـ SwiftUI (T8-14 — §L.9) — مصدرها حصرياً
 * `ar.lproj/Localizable.strings` (الافتراضية) و `en.lproj/Localizable.strings`
 * حسب لغة الحالة المشتركة؛ لا نص ثابت في كود Swift إطلاقاً (AGENTS.md).
 * المرآة النصية لعقد `ArhamStrings.kt` (T8-11) بنفس المصطلحات العربية/الإنجليزية
 * وبنفس مفاتيح موارد Compose (`sharedUI/composeResources`) حرفياً.
 *
 * قوالب العدد موضعية (`%1$ld`/`%2$ld` للأعداد و`%1$@` للنصوص — صيغة .strings)
 * وتُعوَّض عبر `String(format:)` — مرآة `replace("%1\$d", …)` في
 * `ArhamStringsProvider.kt` (نفس دلالة التعويض، ومحولات الصيغة موثقة أدناه).
 */
struct ArhamLocalizedStrings {

    let language: AppLanguage

    private var bundle: Bundle {
        let path = Bundle.main.path(forResource: language.code, ofType: "lproj")
        return path.flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    private func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    // MARK: - العنوان والفترات والشجرة

    var screenTitle: String { string("arham_screen_title") }
    var periodWeek: String { string("arham_period_week") }
    var periodMonth: String { string("arham_period_month") }
    var openTree: String { string("arham_open_tree") }
    var closeTree: String { string("arham_close_tree") }
    var treeTitle: String { string("arham_tree_title") }
    var treeCenterOnMe: String { string("arham_tree_center") }
    var treeEmpty: String { string("arham_tree_empty") }
    var treeAdd: String { string("arham_tree_add") }
    var treePickCandidate: String { string("arham_tree_pick_candidate") }
    /// مرآة ResourceArhamStrings: زر الاتصال في ورقة العقدة يعيد استخدام مفتاح action_call.
    var treeNodeCall: String { string("action_call") }
    var treeNodeEditKinship: String { string("arham_tree_node_edit_kinship") }

    // MARK: - اللوحة (منصّة الأكثر وصلاً + الشارات)

    func podiumTitle(isWeek: Bool) -> String {
        isWeek ? string("arham_podium_week") : string("arham_podium_month")
    }
    var emptyTitle: String { string("arham_empty_title") }
    var emptyBody: String { string("arham_empty_body") }
    var celebrationTitle: String { string("arham_celebration_title") }
    var statCalls: String { string("arham_stats_calls") }
    var statPaused: String { string("arham_stat_paused") }
    var statConnectedLabel: String { string("arham_stat_connected_label") }
    var statGoalLabel: String { string("arham_stats_target") }

    /// `%1$ld من %2$ld` — المتواصَل معهم من الإجمالي.
    func statConnected(contacted: Int, total: Int) -> String {
        String(format: string("arham_stat_connected_value"), contacted, total)
    }

    /// `%1$ld٪` — نسبة تحقيق الهدف.
    func statGoal(percent: Int) -> String {
        String(format: string("arham_stat_goal_value"), percent)
    }

    // MARK: - حالات الصفوف (تفصيل يدوي لصناديق الجمع — نمط details_arham_days_*)

    /// مرآة `statusText` في `ResourceArhamStrings`: اليوم صيغته الخاصة بلا عدد.
    func statusText(_ status: ArhamStatus, daysSinceLastContact: Int?) -> String {
        let days = daysSinceLastContact ?? 0
        switch status {
        case .overdue:
            return days == 0
                ? string("arham_row_overdue_today")
                : String(format: string("arham_row_overdue"), days)
        case .soon:
            return days == 0
                ? string("arham_row_soon_today")
                : String(format: string("arham_row_soon"), days)
        default:
            return string("arham_row_ok")
        }
    }

    // MARK: - التأكيدات (AC-7)

    func confirmationsBadge(count: Int) -> String {
        String(format: string("arham_confirmations_badge"), count)
    }

    /// سطر حدث المكالمة — مع المدة إن وُجدت وإلا الصيغة العادية (الاحتياط الموثق).
    func confirmationEventLine(directionLabel: String, durationMin: Int?) -> String {
        if let durationMin {
            return String(format: string("arham_confirmation_event_duration"), directionLabel, durationMin)
        }
        return String(format: string("arham_confirmation_event_plain"), directionLabel)
    }

    var confirmContacted: String { string("arham_confirm_contacted") }
    var confirmNoAnswer: String { string("arham_confirm_no_answer") }
    var confirmNotRelated: String { string("arham_confirm_not_related") }
    var confirmLater: String { string("arham_confirm_later") }

    var directionOutgoing: String { string("arham_direction_outgoing") }
    var directionIncoming: String { string("arham_direction_incoming") }
    var directionMissed: String { string("arham_direction_missed") }
    var directionUnknown: String { string("arham_direction_unknown") }

    // MARK: - المناسبات (O-5/O-8)

    var occasionsTitle: String { string("arham_occasions_title") }

    /// `%1$@ %2$@` — سياق القرابة مع الاسم (O-8: «عمتك سعاد»).
    func occasionPerson(_ relationLabel: String, _ displayName: String) -> String {
        String(format: string("arham_occasion_person"), relationLabel, displayName)
    }

    func occasionToday(_ personLine: String) -> String {
        String(format: string("arham_occasion_today"), personLine)
    }

    func occasionInDays(_ daysUntil: Int, _ personLine: String) -> String {
        String(format: string("arham_occasion_in_days"), daysUntil, personLine)
    }

    // MARK: - محرّر القرابة (AC-1)

    func kinshipTitle(_ displayName: String) -> String {
        String(format: string("arham_kinship_title"), displayName)
    }
    var kinshipStepRelation: String { string("arham_kinship_step_relation") }
    var kinshipStepFrequency: String { string("arham_kinship_step_frequency") }
    var kinshipParentLabel: String { string("arham_kinship_parent_label") }
    var kinshipParentNone: String { string("arham_kinship_parent_none") }
    var kinshipPauseLabel: String { string("arham_kinship_pause_label") }
    var kinshipPauseOneMonth: String { string("arham_kinship_pause_month") }
    var kinshipSave: String { string("action_save") }
    var kinshipFrequencyRequired: String { string("arham_kinship_frequency_required") }

    func frequencyLabel(_ frequency: ArhamFrequency) -> String {
        switch frequency {
        case .daily: return string("arham_frequency_daily")
        case .weekly: return string("arham_frequency_weekly")
        case .biweekly: return string("arham_frequency_biweekly")
        case .monthly: return string("arham_frequency_monthly")
        case .occasions: return string("arham_frequency_occasions")
        @unknown default: return string("arham_frequency_occasions")
        }
    }

    // MARK: - القرابات والطبقات (مرايا arham_relation_* و layer_*)

    func relationLabel(_ relation: Relation) -> String {
        switch relation {
        case .grandfather: return string("arham_relation_grandfather")
        case .grandmother: return string("arham_relation_grandmother")
        case .father: return string("arham_relation_father")
        case .mother: return string("arham_relation_mother")
        case .brother: return string("arham_relation_brother")
        case .sister: return string("arham_relation_sister")
        case .son: return string("arham_relation_son")
        case .daughter: return string("arham_relation_daughter")
        case .spouse: return string("arham_relation_spouse")
        case .unclePaternal: return string("arham_relation_uncle_paternal")
        case .auntPaternal: return string("arham_relation_aunt_paternal")
        case .uncleMaternal: return string("arham_relation_uncle_maternal")
        case .auntMaternal: return string("arham_relation_aunt_maternal")
        case .cousinPaternal: return string("arham_relation_cousin_paternal")
        case .cousinMaternal: return string("arham_relation_cousin_maternal")
        case .inLaw: return string("arham_relation_in_law")
        case .closeFriend: return string("arham_relation_close_friend")
        case .custom: return string("arham_relation_custom")
        @unknown default: return string("arham_relation_custom")
        }
    }

    func layerLabel(_ layer: KinshipLayer) -> String {
        switch layer {
        case .ancestor: return string("layer_ancestor")
        case .selfBranch: return string("layer_self_branch")
        case .collateralPaternal: return string("layer_collateral_paternal")
        case .collateralMaternal: return string("layer_collateral_maternal")
        case .spouseSide: return string("layer_spouse_side")
        case .other: return string("layer_other")
        @unknown default: return string("layer_other")
        }
    }

    // MARK: - الطوارئ (AC-14 — اللافتة حصراً؛ البداية من مضيف المنصة)

    var emergencyBannerTitle: String { string("arham_emergency_banner_title") }
    var emergencyStop: String { string("arham_emergency_stop") }

    func emergencyAttempts(attempt: Int, max: Int) -> String {
        String(format: string("arham_emergency_attempts"), attempt, max)
    }

    // MARK: - موافقة الفحص (AC-4 — السطح مخفي على iOS: HIDDEN من المصدر المشترك)

    var screeningConsentTitle: String { string("arham_screening_consent_title") }
    var screeningConsentBody: String { string("arham_screening_consent_body") }
    var screeningConsentAction: String { string("arham_screening_consent_action") }
    var screeningConsentDismiss: String { string("arham_screening_consent_dismiss") }

    // MARK: - أفعال مشتركة (مرايا action_*)

    var actionCancel: String { string("action_cancel") }
    var rowCallLabel: String { string("action_call") }

    /// §L.4: بديل «نسخ الرقم» حين يفشل `canOpenURL("tel:")` (iPad/بلا شريحة).
    var copyNumber: String { string("arham_action_copy_number") }
}
