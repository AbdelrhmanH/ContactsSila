package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.Category
import app.sila.feature.contacts.domain.DuplicateReasonKind
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.dup_back_description
import contactssila.sharedui.generated.resources.dup_call_member
import contactssila.sharedui.generated.resources.dup_confidence_high
import contactssila.sharedui.generated.resources.dup_confidence_low
import contactssila.sharedui.generated.resources.dup_confidence_medium
import contactssila.sharedui.generated.resources.dup_empty_message
import contactssila.sharedui.generated.resources.dup_empty_title
import contactssila.sharedui.generated.resources.dup_error_merge
import contactssila.sharedui.generated.resources.dup_error_undo
import contactssila.sharedui.generated.resources.dup_loading
import contactssila.sharedui.generated.resources.dup_merge_all_confirmed
import contactssila.sharedui.generated.resources.dup_merge_button
import contactssila.sharedui.generated.resources.dup_not_duplicates_button
import contactssila.sharedui.generated.resources.dup_preview_notes_merged
import contactssila.sharedui.generated.resources.dup_preview_summary
import contactssila.sharedui.generated.resources.dup_preview_title
import contactssila.sharedui.generated.resources.dup_reason_same_email
import contactssila.sharedui.generated.resources.dup_reason_same_name
import contactssila.sharedui.generated.resources.dup_reason_same_phone
import contactssila.sharedui.generated.resources.dup_reason_similar_name
import contactssila.sharedui.generated.resources.dup_ringtone_conflict
import contactssila.sharedui.generated.resources.dup_screen_title
import contactssila.sharedui.generated.resources.dup_undo_message
import contactssila.sharedui.generated.resources.action_undo
import contactssila.sharedui.generated.resources.category_emergency
import contactssila.sharedui.generated.resources.category_family
import contactssila.sharedui.generated.resources.category_friends
import contactssila.sharedui.generated.resources.category_services
import contactssila.sharedui.generated.resources.category_uncategorized
import contactssila.sharedui.generated.resources.category_work
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشة مراجعة المكررات (T7 · Issue #28) من موارد الترجمة الحقيقية:
 * `values/` (العربية — الافتراضية) و `values-en/` — بنمط مزوّدات T5/T6. لا نص ثابت
 * في كود الواجهة (AGENTS.md)، والقوالب تُعوَّض يدوياً في `commonMain` (لا
 * `String.format` في الكود المشترك).
 */
@Composable
fun rememberDuplicatesReviewStrings(): DuplicatesReviewStrings {
    val family = stringResource(Res.string.category_family)
    val friends = stringResource(Res.string.category_friends)
    val work = stringResource(Res.string.category_work)
    val services = stringResource(Res.string.category_services)
    val emergency = stringResource(Res.string.category_emergency)
    val uncategorized = stringResource(Res.string.category_uncategorized)

    val reasonSamePhone = stringResource(Res.string.dup_reason_same_phone)
    val reasonSameEmail = stringResource(Res.string.dup_reason_same_email)
    val reasonSameName = stringResource(Res.string.dup_reason_same_name)
    val reasonSimilarName = stringResource(Res.string.dup_reason_similar_name)
    val previewSummary = stringResource(Res.string.dup_preview_summary)
    val mergeAllConfirmed = stringResource(Res.string.dup_merge_all_confirmed)

    return ResourceDuplicatesReviewStrings(
        screenTitle = stringResource(Res.string.dup_screen_title),
        backDescription = stringResource(Res.string.dup_back_description),
        loading = stringResource(Res.string.dup_loading),
        emptyTitle = stringResource(Res.string.dup_empty_title),
        emptyMessage = stringResource(Res.string.dup_empty_message),
        confidenceHigh = stringResource(Res.string.dup_confidence_high),
        confidenceMedium = stringResource(Res.string.dup_confidence_medium),
        confidenceLow = stringResource(Res.string.dup_confidence_low),
        reasonLabel = { kind, detail ->
            when (kind) {
                DuplicateReasonKind.SAME_PHONE -> reasonSamePhone.replace("%s", detail)
                DuplicateReasonKind.SAME_EMAIL -> reasonSameEmail.replace("%s", detail)
                DuplicateReasonKind.SAME_NAME -> reasonSameName.replace("%s", detail)
                DuplicateReasonKind.SIMILAR_NAME -> reasonSimilarName.replace("%s", detail)
            }
        },
        categoryTitle = { category ->
            when (category) {
                Category.FAMILY -> family
                Category.FRIENDS -> friends
                Category.WORK -> work
                Category.SERVICES -> services
                Category.EMERGENCY -> emergency
                Category.UNCATEGORIZED -> uncategorized
            }
        },
        previewTitle = stringResource(Res.string.dup_preview_title),
        previewSummary = { phones, emails ->
            previewSummary
                .replaceFirst("%d", phones.toString())
                .replaceFirst("%d", emails.toString())
        },
        previewNotesMerged = stringResource(Res.string.dup_preview_notes_merged),
        ringtoneConflictNotice = stringResource(Res.string.dup_ringtone_conflict),
        mergeButton = stringResource(Res.string.dup_merge_button),
        notDuplicatesButton = stringResource(Res.string.dup_not_duplicates_button),
        mergeAllConfirmed = { count -> mergeAllConfirmed.replaceFirst("%d", count.toString()) },
        undoMessage = stringResource(Res.string.dup_undo_message),
        undo = stringResource(Res.string.action_undo),
        callMemberLabel = stringResource(Res.string.dup_call_member),
        errorMerge = stringResource(Res.string.dup_error_merge),
        errorUndo = stringResource(Res.string.dup_error_undo),
    )
}

/**
 * تنفيذ نصوص الموارد — كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا.
 * (`dup_open_member_description` مخصصة لمرايا SwiftUI في S12 — لا جزء من هذا العقد.)
 */
private data class ResourceDuplicatesReviewStrings(
    override val screenTitle: String,
    override val backDescription: String,
    override val loading: String,
    override val emptyTitle: String,
    override val emptyMessage: String,
    override val confidenceHigh: String,
    override val confidenceMedium: String,
    override val confidenceLow: String,
    override val reasonLabel: (kind: DuplicateReasonKind, detail: String) -> String,
    override val categoryTitle: (category: Category) -> String,
    override val previewTitle: String,
    override val previewSummary: (phones: Int, emails: Int) -> String,
    override val previewNotesMerged: String,
    override val ringtoneConflictNotice: String,
    override val mergeButton: String,
    override val notDuplicatesButton: String,
    override val mergeAllConfirmed: (count: Int) -> String,
    override val undoMessage: String,
    override val undo: String,
    override val callMemberLabel: String,
    override val errorMerge: String,
    override val errorUndo: String,
) : DuplicatesReviewStrings
