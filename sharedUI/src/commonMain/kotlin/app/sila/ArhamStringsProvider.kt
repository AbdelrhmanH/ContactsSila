package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation
import app.sila.feature.arham.presentation.ArhamStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.action_call
import contactssila.sharedui.generated.resources.action_cancel
import contactssila.sharedui.generated.resources.action_save
import contactssila.sharedui.generated.resources.arham_celebration_title
import contactssila.sharedui.generated.resources.arham_close_tree
import contactssila.sharedui.generated.resources.arham_confirm_contacted
import contactssila.sharedui.generated.resources.arham_confirm_later
import contactssila.sharedui.generated.resources.arham_confirm_no_answer
import contactssila.sharedui.generated.resources.arham_confirm_not_related
import contactssila.sharedui.generated.resources.arham_confirmation_event_duration
import contactssila.sharedui.generated.resources.arham_confirmation_event_plain
import contactssila.sharedui.generated.resources.arham_confirmations_badge
import contactssila.sharedui.generated.resources.arham_direction_incoming
import contactssila.sharedui.generated.resources.arham_direction_missed
import contactssila.sharedui.generated.resources.arham_direction_outgoing
import contactssila.sharedui.generated.resources.arham_direction_unknown
import contactssila.sharedui.generated.resources.arham_empty_body
import contactssila.sharedui.generated.resources.arham_empty_title
import contactssila.sharedui.generated.resources.arham_emergency_attempts
import contactssila.sharedui.generated.resources.arham_emergency_banner_title
import contactssila.sharedui.generated.resources.arham_emergency_stop
import contactssila.sharedui.generated.resources.arham_frequency_biweekly
import contactssila.sharedui.generated.resources.arham_frequency_daily
import contactssila.sharedui.generated.resources.arham_frequency_monthly
import contactssila.sharedui.generated.resources.arham_frequency_occasions
import contactssila.sharedui.generated.resources.arham_frequency_weekly
import contactssila.sharedui.generated.resources.arham_kinship_frequency_required
import contactssila.sharedui.generated.resources.arham_kinship_parent_label
import contactssila.sharedui.generated.resources.arham_kinship_parent_none
import contactssila.sharedui.generated.resources.arham_kinship_pause_label
import contactssila.sharedui.generated.resources.arham_kinship_pause_month
import contactssila.sharedui.generated.resources.arham_kinship_step_frequency
import contactssila.sharedui.generated.resources.arham_kinship_step_relation
import contactssila.sharedui.generated.resources.arham_kinship_title
import contactssila.sharedui.generated.resources.arham_open_tree
import contactssila.sharedui.generated.resources.arham_occasion_in_days
import contactssila.sharedui.generated.resources.arham_occasion_person
import contactssila.sharedui.generated.resources.arham_occasion_today
import contactssila.sharedui.generated.resources.arham_occasions_title
import contactssila.sharedui.generated.resources.arham_period_month
import contactssila.sharedui.generated.resources.arham_period_week
import contactssila.sharedui.generated.resources.arham_podium_month
import contactssila.sharedui.generated.resources.arham_podium_week
import contactssila.sharedui.generated.resources.arham_relation_aunt_maternal
import contactssila.sharedui.generated.resources.arham_relation_aunt_paternal
import contactssila.sharedui.generated.resources.arham_relation_brother
import contactssila.sharedui.generated.resources.arham_relation_close_friend
import contactssila.sharedui.generated.resources.arham_relation_cousin_maternal
import contactssila.sharedui.generated.resources.arham_relation_cousin_paternal
import contactssila.sharedui.generated.resources.arham_relation_custom
import contactssila.sharedui.generated.resources.arham_relation_daughter
import contactssila.sharedui.generated.resources.arham_relation_father
import contactssila.sharedui.generated.resources.arham_relation_grandfather
import contactssila.sharedui.generated.resources.arham_relation_grandmother
import contactssila.sharedui.generated.resources.arham_relation_in_law
import contactssila.sharedui.generated.resources.arham_relation_mother
import contactssila.sharedui.generated.resources.arham_relation_sister
import contactssila.sharedui.generated.resources.arham_relation_son
import contactssila.sharedui.generated.resources.arham_relation_spouse
import contactssila.sharedui.generated.resources.arham_relation_uncle_maternal
import contactssila.sharedui.generated.resources.arham_relation_uncle_paternal
import contactssila.sharedui.generated.resources.arham_row_ok
import contactssila.sharedui.generated.resources.arham_row_overdue
import contactssila.sharedui.generated.resources.arham_row_overdue_today
import contactssila.sharedui.generated.resources.arham_row_soon
import contactssila.sharedui.generated.resources.arham_row_soon_today
import contactssila.sharedui.generated.resources.arham_screen_title
import contactssila.sharedui.generated.resources.arham_screening_consent_action
import contactssila.sharedui.generated.resources.arham_screening_consent_body
import contactssila.sharedui.generated.resources.arham_screening_consent_dismiss
import contactssila.sharedui.generated.resources.arham_screening_consent_title
import contactssila.sharedui.generated.resources.arham_stat_connected_label
import contactssila.sharedui.generated.resources.arham_stat_connected_value
import contactssila.sharedui.generated.resources.arham_stat_goal_value
import contactssila.sharedui.generated.resources.arham_stat_paused
import contactssila.sharedui.generated.resources.arham_stats_calls
import contactssila.sharedui.generated.resources.arham_stats_target
import contactssila.sharedui.generated.resources.arham_tree_add
import contactssila.sharedui.generated.resources.arham_tree_center
import contactssila.sharedui.generated.resources.arham_tree_empty
import contactssila.sharedui.generated.resources.arham_tree_node_edit_kinship
import contactssila.sharedui.generated.resources.arham_tree_pick_candidate
import contactssila.sharedui.generated.resources.arham_tree_title
import contactssila.sharedui.generated.resources.layer_ancestor
import contactssila.sharedui.generated.resources.layer_collateral_maternal
import contactssila.sharedui.generated.resources.layer_collateral_paternal
import contactssila.sharedui.generated.resources.layer_other
import contactssila.sharedui.generated.resources.layer_self_branch
import contactssila.sharedui.generated.resources.layer_spouse_side
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشات أرحام (T8-11 · Issue #29) من موارد الترجمة الحقيقية: `values/`
 * (العربية — الافتراضية) و`values-en/`. لا نص ثابت في كود الواجهة إطلاقاً (AGENTS.md).
 * القوالب ذات العدد تستخدم `%1$d`/`%2$s` وتُعوَّض يدوياً في commonMain (لا
 * `String.format` — غير متاح في الكود المشترك) — نفس نمط `rememberContactsStrings`.
 * صناديق الجمع تُفصَل يدوياً (اليوم مقابل الأيام — نمط `details_arham_days_*`).
 */
@Composable
fun rememberArhamStrings(): ArhamStrings {
    return ResourceArhamStrings(
        screenTitle = stringResource(Res.string.arham_screen_title),
        statCalls = stringResource(Res.string.arham_stats_calls),
        statPaused = stringResource(Res.string.arham_stat_paused),
        statConnectedLabel = stringResource(Res.string.arham_stat_connected_label),
        statGoalLabel = stringResource(Res.string.arham_stats_target),
        periodWeek = stringResource(Res.string.arham_period_week),
        periodMonth = stringResource(Res.string.arham_period_month),
        openTree = stringResource(Res.string.arham_open_tree),
        closeTree = stringResource(Res.string.arham_close_tree),
        emptyTitle = stringResource(Res.string.arham_empty_title),
        emptyBody = stringResource(Res.string.arham_empty_body),
        celebrationTitle = stringResource(Res.string.arham_celebration_title),
        actionCancel = stringResource(Res.string.action_cancel),
        rowCallLabel = stringResource(Res.string.action_call),
        confirmContacted = stringResource(Res.string.arham_confirm_contacted),
        confirmNoAnswer = stringResource(Res.string.arham_confirm_no_answer),
        confirmNotRelated = stringResource(Res.string.arham_confirm_not_related),
        confirmLater = stringResource(Res.string.arham_confirm_later),
        directionOutgoing = stringResource(Res.string.arham_direction_outgoing),
        directionIncoming = stringResource(Res.string.arham_direction_incoming),
        directionMissed = stringResource(Res.string.arham_direction_missed),
        directionUnknown = stringResource(Res.string.arham_direction_unknown),
        occasionsTitle = stringResource(Res.string.arham_occasions_title),
        kinshipStepRelation = stringResource(Res.string.arham_kinship_step_relation),
        kinshipStepFrequency = stringResource(Res.string.arham_kinship_step_frequency),
        kinshipParentLabel = stringResource(Res.string.arham_kinship_parent_label),
        kinshipParentNone = stringResource(Res.string.arham_kinship_parent_none),
        kinshipPauseLabel = stringResource(Res.string.arham_kinship_pause_label),
        kinshipPauseOneMonth = stringResource(Res.string.arham_kinship_pause_month),
        kinshipSave = stringResource(Res.string.action_save),
        kinshipFrequencyRequired = stringResource(Res.string.arham_kinship_frequency_required),
        treeTitle = stringResource(Res.string.arham_tree_title),
        treeCenterOnMe = stringResource(Res.string.arham_tree_center),
        treeEmpty = stringResource(Res.string.arham_tree_empty),
        treeAdd = stringResource(Res.string.arham_tree_add),
        treePickCandidate = stringResource(Res.string.arham_tree_pick_candidate),
        treeNodeCall = stringResource(Res.string.action_call),
        treeNodeEditKinship = stringResource(Res.string.arham_tree_node_edit_kinship),
        emergencyBannerTitle = stringResource(Res.string.arham_emergency_banner_title),
        emergencyStop = stringResource(Res.string.arham_emergency_stop),
        screeningConsentTitle = stringResource(Res.string.arham_screening_consent_title),
        screeningConsentBody = stringResource(Res.string.arham_screening_consent_body),
        screeningConsentAction = stringResource(Res.string.arham_screening_consent_action),
        screeningConsentDismiss = stringResource(Res.string.arham_screening_consent_dismiss),
        podiumWeek = stringResource(Res.string.arham_podium_week),
        podiumMonth = stringResource(Res.string.arham_podium_month),
        statusOverdueTemplate = stringResource(Res.string.arham_row_overdue),
        statusOverdueToday = stringResource(Res.string.arham_row_overdue_today),
        statusSoonTemplate = stringResource(Res.string.arham_row_soon),
        statusSoonToday = stringResource(Res.string.arham_row_soon_today),
        statusOk = stringResource(Res.string.arham_row_ok),
        confirmationsBadgeTemplate = stringResource(Res.string.arham_confirmations_badge),
        confirmationEventDurationTemplate = stringResource(Res.string.arham_confirmation_event_duration),
        confirmationEventPlainTemplate = stringResource(Res.string.arham_confirmation_event_plain),
        occasionPersonTemplate = stringResource(Res.string.arham_occasion_person),
        occasionTodayTemplate = stringResource(Res.string.arham_occasion_today),
        occasionInDaysTemplate = stringResource(Res.string.arham_occasion_in_days),
        kinshipTitleTemplate = stringResource(Res.string.arham_kinship_title),
        statConnectedValueTemplate = stringResource(Res.string.arham_stat_connected_value),
        statGoalValueTemplate = stringResource(Res.string.arham_stat_goal_value),
        emergencyAttemptsTemplate = stringResource(Res.string.arham_emergency_attempts),
        frequencyDaily = stringResource(Res.string.arham_frequency_daily),
        frequencyWeekly = stringResource(Res.string.arham_frequency_weekly),
        frequencyBiweekly = stringResource(Res.string.arham_frequency_biweekly),
        frequencyMonthly = stringResource(Res.string.arham_frequency_monthly),
        frequencyOccasions = stringResource(Res.string.arham_frequency_occasions),
        grandfather = stringResource(Res.string.arham_relation_grandfather),
        grandmother = stringResource(Res.string.arham_relation_grandmother),
        father = stringResource(Res.string.arham_relation_father),
        mother = stringResource(Res.string.arham_relation_mother),
        brother = stringResource(Res.string.arham_relation_brother),
        sister = stringResource(Res.string.arham_relation_sister),
        son = stringResource(Res.string.arham_relation_son),
        daughter = stringResource(Res.string.arham_relation_daughter),
        spouse = stringResource(Res.string.arham_relation_spouse),
        unclePaternal = stringResource(Res.string.arham_relation_uncle_paternal),
        auntPaternal = stringResource(Res.string.arham_relation_aunt_paternal),
        uncleMaternal = stringResource(Res.string.arham_relation_uncle_maternal),
        auntMaternal = stringResource(Res.string.arham_relation_aunt_maternal),
        cousinPaternal = stringResource(Res.string.arham_relation_cousin_paternal),
        cousinMaternal = stringResource(Res.string.arham_relation_cousin_maternal),
        inLaw = stringResource(Res.string.arham_relation_in_law),
        closeFriend = stringResource(Res.string.arham_relation_close_friend),
        custom = stringResource(Res.string.arham_relation_custom),
        layerAncestor = stringResource(Res.string.layer_ancestor),
        layerSelfBranch = stringResource(Res.string.layer_self_branch),
        layerCollateralPaternal = stringResource(Res.string.layer_collateral_paternal),
        layerCollateralMaternal = stringResource(Res.string.layer_collateral_maternal),
        layerSpouseSide = stringResource(Res.string.layer_spouse_side),
        layerOther = stringResource(Res.string.layer_other),
    )
}

/**
 * تنفيذ نصوص الموارد — كل القيم قادمة من موارد الترجمة ولا حرف مضمن في الكود.
 */
private class ResourceArhamStrings(
    override val screenTitle: String,
    override val statCalls: String,
    override val statPaused: String,
    override val statConnectedLabel: String,
    override val statGoalLabel: String,
    override val periodWeek: String,
    override val periodMonth: String,
    override val openTree: String,
    override val closeTree: String,
    override val emptyTitle: String,
    override val emptyBody: String,
    override val celebrationTitle: String,
    override val actionCancel: String,
    override val rowCallLabel: String,
    override val confirmContacted: String,
    override val confirmNoAnswer: String,
    override val confirmNotRelated: String,
    override val confirmLater: String,
    override val directionOutgoing: String,
    override val directionIncoming: String,
    override val directionMissed: String,
    override val directionUnknown: String,
    override val occasionsTitle: String,
    override val kinshipStepRelation: String,
    override val kinshipStepFrequency: String,
    override val kinshipParentLabel: String,
    override val kinshipParentNone: String,
    override val kinshipPauseLabel: String,
    override val kinshipPauseOneMonth: String,
    override val kinshipSave: String,
    override val kinshipFrequencyRequired: String,
    override val treeTitle: String,
    override val treeCenterOnMe: String,
    override val treeEmpty: String,
    override val treeAdd: String,
    override val treePickCandidate: String,
    override val treeNodeCall: String,
    override val treeNodeEditKinship: String,
    override val emergencyBannerTitle: String,
    override val emergencyStop: String,
    override val screeningConsentTitle: String,
    override val screeningConsentBody: String,
    override val screeningConsentAction: String,
    override val screeningConsentDismiss: String,
    // قوالب وعلامات مرجعية
    private val podiumWeek: String,
    private val podiumMonth: String,
    private val statusOverdueTemplate: String,
    private val statusOverdueToday: String,
    private val statusSoonTemplate: String,
    private val statusSoonToday: String,
    private val statusOk: String,
    private val confirmationsBadgeTemplate: String,
    private val confirmationEventDurationTemplate: String,
    private val confirmationEventPlainTemplate: String,
    private val occasionPersonTemplate: String,
    private val occasionTodayTemplate: String,
    private val occasionInDaysTemplate: String,
    private val kinshipTitleTemplate: String,
    private val statConnectedValueTemplate: String,
    private val statGoalValueTemplate: String,
    private val emergencyAttemptsTemplate: String,
    private val frequencyDaily: String,
    private val frequencyWeekly: String,
    private val frequencyBiweekly: String,
    private val frequencyMonthly: String,
    private val frequencyOccasions: String,
    private val grandfather: String,
    private val grandmother: String,
    private val father: String,
    private val mother: String,
    private val brother: String,
    private val sister: String,
    private val son: String,
    private val daughter: String,
    private val spouse: String,
    private val unclePaternal: String,
    private val auntPaternal: String,
    private val uncleMaternal: String,
    private val auntMaternal: String,
    private val cousinPaternal: String,
    private val cousinMaternal: String,
    private val inLaw: String,
    private val closeFriend: String,
    private val custom: String,
    private val layerAncestor: String,
    private val layerSelfBranch: String,
    private val layerCollateralPaternal: String,
    private val layerCollateralMaternal: String,
    private val layerSpouseSide: String,
    private val layerOther: String,
) : ArhamStrings {

    override fun podiumTitle(isWeek: Boolean): String = if (isWeek) podiumWeek else podiumMonth

    override fun statConnected(contacted: Int, total: Int): String =
        statConnectedValueTemplate
            .replace("%1\$d", contacted.toString())
            .replace("%2\$d", total.toString())

    override fun statGoal(percent: Int): String =
        statGoalValueTemplate.replace("%1\$d", percent.toString())

    override fun statusText(status: ArhamStatus, daysSinceLastContact: Int?): String {
        val days = daysSinceLastContact ?: 0
        return when (status) {
            ArhamStatus.OVERDUE ->
                if (days == 0) statusOverdueToday else statusOverdueTemplate.replace("%1\$d", days.toString())
            ArhamStatus.SOON ->
                if (days == 0) statusSoonToday else statusSoonTemplate.replace("%1\$d", days.toString())
            ArhamStatus.OK -> statusOk
        }
    }

    override fun confirmationsBadge(count: Int): String =
        confirmationsBadgeTemplate.replace("%1\$d", count.toString())

    override fun confirmationEventLine(directionLabel: String, durationMin: Int?): String =
        if (durationMin != null) {
            confirmationEventDurationTemplate
                .replace("%1\$s", directionLabel)
                .replace("%2\$d", durationMin.toString())
        } else {
            confirmationEventPlainTemplate.replace("%1\$s", directionLabel)
        }

    override fun occasionPerson(relationLabel: String, displayName: String): String =
        occasionPersonTemplate
            .replace("%1\$s", relationLabel)
            .replace("%2\$s", displayName)

    override fun occasionToday(personLine: String): String =
        occasionTodayTemplate.replace("%1\$s", personLine)

    override fun occasionInDays(daysUntil: Int, personLine: String): String =
        occasionInDaysTemplate
            .replace("%1\$d", daysUntil.toString())
            .replace("%2\$s", personLine)

    override fun kinshipTitle(displayName: String): String =
        kinshipTitleTemplate.replace("%1\$s", displayName)

    override fun frequencyLabel(frequency: ArhamFrequency): String = when (frequency) {
        ArhamFrequency.DAILY -> frequencyDaily
        ArhamFrequency.WEEKLY -> frequencyWeekly
        ArhamFrequency.BIWEEKLY -> frequencyBiweekly
        ArhamFrequency.MONTHLY -> frequencyMonthly
        ArhamFrequency.OCCASIONS -> frequencyOccasions
    }

    override fun relationLabel(relation: Relation): String = when (relation) {
        Relation.GRANDFATHER -> grandfather
        Relation.GRANDMOTHER -> grandmother
        Relation.FATHER -> father
        Relation.MOTHER -> mother
        Relation.BROTHER -> brother
        Relation.SISTER -> sister
        Relation.SON -> son
        Relation.DAUGHTER -> daughter
        Relation.SPOUSE -> spouse
        Relation.UNCLE_PATERNAL -> unclePaternal
        Relation.AUNT_PATERNAL -> auntPaternal
        Relation.UNCLE_MATERNAL -> uncleMaternal
        Relation.AUNT_MATERNAL -> auntMaternal
        Relation.COUSIN_PATERNAL -> cousinPaternal
        Relation.COUSIN_MATERNAL -> cousinMaternal
        Relation.IN_LAW -> inLaw
        Relation.CLOSE_FRIEND -> closeFriend
        Relation.CUSTOM -> custom
    }

    override fun layerLabel(layer: KinshipLayer): String = when (layer) {
        KinshipLayer.ANCESTOR -> layerAncestor
        KinshipLayer.SELF_BRANCH -> layerSelfBranch
        KinshipLayer.COLLATERAL_PATERNAL -> layerCollateralPaternal
        KinshipLayer.COLLATERAL_MATERNAL -> layerCollateralMaternal
        KinshipLayer.SPOUSE_SIDE -> layerSpouseSide
        KinshipLayer.OTHER -> layerOther
    }

    override fun emergencyAttempts(attempt: Int, max: Int): String =
        emergencyAttemptsTemplate
            .replace("%1\$d", attempt.toString())
            .replace("%2\$d", max.toString())
}
