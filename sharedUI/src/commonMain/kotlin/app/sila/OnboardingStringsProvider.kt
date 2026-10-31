package app.sila

import androidx.compose.runtime.Composable
import app.sila.feature.contacts.presentation.onboarding.OnboardingStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.onboarding_action_continue
import contactssila.sharedui.generated.resources.onboarding_action_start
import contactssila.sharedui.generated.resources.onboarding_action_try_again
import contactssila.sharedui.generated.resources.onboarding_error_title
import contactssila.sharedui.generated.resources.onboarding_guest_body
import contactssila.sharedui.generated.resources.onboarding_guest_login_note
import contactssila.sharedui.generated.resources.onboarding_guest_skip_button
import contactssila.sharedui.generated.resources.onboarding_guest_title
import contactssila.sharedui.generated.resources.onboarding_organizing_body
import contactssila.sharedui.generated.resources.onboarding_organizing_title
import contactssila.sharedui.generated.resources.onboarding_permission_body
import contactssila.sharedui.generated.resources.onboarding_permission_denied_forever_body
import contactssila.sharedui.generated.resources.onboarding_permission_denied_once_body
import contactssila.sharedui.generated.resources.onboarding_permission_grant_button
import contactssila.sharedui.generated.resources.onboarding_permission_limited_body
import contactssila.sharedui.generated.resources.onboarding_permission_open_settings_button
import contactssila.sharedui.generated.resources.onboarding_permission_skip_button
import contactssila.sharedui.generated.resources.onboarding_permission_title
import contactssila.sharedui.generated.resources.onboarding_top_five_body
import contactssila.sharedui.generated.resources.onboarding_top_five_counter_template
import contactssila.sharedui.generated.resources.onboarding_top_five_empty_body
import contactssila.sharedui.generated.resources.onboarding_top_five_empty_title
import contactssila.sharedui.generated.resources.onboarding_top_five_error_body
import contactssila.sharedui.generated.resources.onboarding_top_five_limited_body
import contactssila.sharedui.generated.resources.onboarding_top_five_loading_label
import contactssila.sharedui.generated.resources.onboarding_top_five_no_results_label
import contactssila.sharedui.generated.resources.onboarding_top_five_permission_missing_body
import contactssila.sharedui.generated.resources.onboarding_top_five_save_button
import contactssila.sharedui.generated.resources.onboarding_top_five_search_hint
import contactssila.sharedui.generated.resources.onboarding_top_five_skip_button
import contactssila.sharedui.generated.resources.onboarding_top_five_title
import contactssila.sharedui.generated.resources.onboarding_welcome_body_arham
import contactssila.sharedui.generated.resources.onboarding_welcome_body_live
import contactssila.sharedui.generated.resources.onboarding_welcome_body_organize
import contactssila.sharedui.generated.resources.onboarding_welcome_title_arham
import contactssila.sharedui.generated.resources.onboarding_welcome_title_live
import contactssila.sharedui.generated.resources.onboarding_welcome_title_organize
import contactssila.sharedui.generated.resources.relation_brother
import contactssila.sharedui.generated.resources.relation_close_friend
import contactssila.sharedui.generated.resources.relation_custom
import contactssila.sharedui.generated.resources.relation_daughter
import contactssila.sharedui.generated.resources.relation_father
import contactssila.sharedui.generated.resources.relation_in_law
import contactssila.sharedui.generated.resources.relation_mother
import contactssila.sharedui.generated.resources.relation_sister
import contactssila.sharedui.generated.resources.relation_son
import contactssila.sharedui.generated.resources.relation_spouse
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشات التهيئة (Issue #26 — T5) من موارد الترجمة الحقيقية:
 * `sharedUI/src/commonMain/composeResources/values/` (العربية — الافتراضية)
 * و `values-en/` (الإنجليزية). لا نص ثابت في كود الواجهة إطلاقاً (AGENTS.md).
 *
 * ملاحظة (تُتابع في المرحلة 1 أيضاً): تحليل الموارد في Compose Multiplatform يتجه
 * إلى لغة النظام، والقيمة الافتراضية للملف default هي العربية RTL؛ تجاوز لغة
 * النظام من داخل التطبيق غير متاح عبر واجهة عامة في CMP 1.12 (تُلمس في تقرير PR).
 */
@Composable
fun rememberOnboardingStrings(): OnboardingStrings =
    ResourceOnboardingStrings(
        welcomeTitleOrganize = stringResource(Res.string.onboarding_welcome_title_organize),
        welcomeBodyOrganize = stringResource(Res.string.onboarding_welcome_body_organize),
        welcomeTitleLive = stringResource(Res.string.onboarding_welcome_title_live),
        welcomeBodyLive = stringResource(Res.string.onboarding_welcome_body_live),
        welcomeTitleArham = stringResource(Res.string.onboarding_welcome_title_arham),
        welcomeBodyArham = stringResource(Res.string.onboarding_welcome_body_arham),
        actionStart = stringResource(Res.string.onboarding_action_start),
        actionContinue = stringResource(Res.string.onboarding_action_continue),
        permissionTitle = stringResource(Res.string.onboarding_permission_title),
        permissionBody = stringResource(Res.string.onboarding_permission_body),
        permissionGrantButton = stringResource(Res.string.onboarding_permission_grant_button),
        permissionSkipButton = stringResource(Res.string.onboarding_permission_skip_button),
        permissionDeniedOnceBody = stringResource(Res.string.onboarding_permission_denied_once_body),
        permissionDeniedForeverBody = stringResource(Res.string.onboarding_permission_denied_forever_body),
        permissionOpenSettingsButton = stringResource(Res.string.onboarding_permission_open_settings_button),
        permissionLimitedBody = stringResource(Res.string.onboarding_permission_limited_body),
        guestTitle = stringResource(Res.string.onboarding_guest_title),
        guestBody = stringResource(Res.string.onboarding_guest_body),
        guestLoginNote = stringResource(Res.string.onboarding_guest_login_note),
        guestSkipButton = stringResource(Res.string.onboarding_guest_skip_button),
        topFiveTitle = stringResource(Res.string.onboarding_top_five_title),
        topFiveBody = stringResource(Res.string.onboarding_top_five_body),
        topFiveSearchHint = stringResource(Res.string.onboarding_top_five_search_hint),
        topFiveLoadingLabel = stringResource(Res.string.onboarding_top_five_loading_label),
        topFiveEmptyTitle = stringResource(Res.string.onboarding_top_five_empty_title),
        topFiveEmptyBody = stringResource(Res.string.onboarding_top_five_empty_body),
        topFivePermissionMissingBody = stringResource(Res.string.onboarding_top_five_permission_missing_body),
        topFiveLimitedBody = stringResource(Res.string.onboarding_top_five_limited_body),
        topFiveNoResultsLabel = stringResource(Res.string.onboarding_top_five_no_results_label),
        topFiveErrorBody = stringResource(Res.string.onboarding_top_five_error_body),
        errorTitle = stringResource(Res.string.onboarding_error_title),
        errorRetryButton = stringResource(Res.string.onboarding_action_try_again),
        topFiveSaveButton = stringResource(Res.string.onboarding_top_five_save_button),
        topFiveSkipButton = stringResource(Res.string.onboarding_top_five_skip_button),
        topFiveCounterTemplate = stringResource(Res.string.onboarding_top_five_counter_template),
        relationFather = stringResource(Res.string.relation_father),
        relationMother = stringResource(Res.string.relation_mother),
        relationBrother = stringResource(Res.string.relation_brother),
        relationSister = stringResource(Res.string.relation_sister),
        relationSpouse = stringResource(Res.string.relation_spouse),
        relationSon = stringResource(Res.string.relation_son),
        relationDaughter = stringResource(Res.string.relation_daughter),
        relationInLaw = stringResource(Res.string.relation_in_law),
        relationCloseFriend = stringResource(Res.string.relation_close_friend),
        relationCustom = stringResource(Res.string.relation_custom),
        organizingTitle = stringResource(Res.string.onboarding_organizing_title),
        organizingBody = stringResource(Res.string.onboarding_organizing_body)
    )

/**
 * تنفيذ نصوص الموارد (كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا).
 */
private data class ResourceOnboardingStrings(
    override val welcomeTitleOrganize: String,
    override val welcomeBodyOrganize: String,
    override val welcomeTitleLive: String,
    override val welcomeBodyLive: String,
    override val welcomeTitleArham: String,
    override val welcomeBodyArham: String,
    override val actionStart: String,
    override val actionContinue: String,
    override val permissionTitle: String,
    override val permissionBody: String,
    override val permissionGrantButton: String,
    override val permissionSkipButton: String,
    override val permissionDeniedOnceBody: String,
    override val permissionDeniedForeverBody: String,
    override val permissionOpenSettingsButton: String,
    override val permissionLimitedBody: String,
    override val guestTitle: String,
    override val guestBody: String,
    override val guestLoginNote: String,
    override val guestSkipButton: String,
    override val topFiveTitle: String,
    override val topFiveBody: String,
    override val topFiveSearchHint: String,
    override val topFiveLoadingLabel: String,
    override val topFiveEmptyTitle: String,
    override val topFiveEmptyBody: String,
    override val topFivePermissionMissingBody: String,
    override val topFiveLimitedBody: String,
    override val topFiveNoResultsLabel: String,
    override val topFiveErrorBody: String,
    override val errorTitle: String,
    override val errorRetryButton: String,
    override val topFiveSaveButton: String,
    override val topFiveSkipButton: String,
    override val topFiveCounterTemplate: String,
    override val relationFather: String,
    override val relationMother: String,
    override val relationBrother: String,
    override val relationSister: String,
    override val relationSpouse: String,
    override val relationSon: String,
    override val relationDaughter: String,
    override val relationInLaw: String,
    override val relationCloseFriend: String,
    override val relationCustom: String,
    override val organizingTitle: String,
    override val organizingBody: String
) : OnboardingStrings
