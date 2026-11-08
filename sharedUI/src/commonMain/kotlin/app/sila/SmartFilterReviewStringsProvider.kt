package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.Category
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.category_emergency
import contactssila.sharedui.generated.resources.category_family
import contactssila.sharedui.generated.resources.category_friends
import contactssila.sharedui.generated.resources.category_services
import contactssila.sharedui.generated.resources.category_uncategorized
import contactssila.sharedui.generated.resources.category_work
import contactssila.sharedui.generated.resources.sf_apply_selected
import contactssila.sharedui.generated.resources.sf_back_description
import contactssila.sharedui.generated.resources.sf_confidence_high
import contactssila.sharedui.generated.resources.sf_confidence_low
import contactssila.sharedui.generated.resources.sf_confidence_medium
import contactssila.sharedui.generated.resources.sf_empty_message
import contactssila.sharedui.generated.resources.sf_empty_title
import contactssila.sharedui.generated.resources.sf_error_apply
import contactssila.sharedui.generated.resources.sf_error_arham_link
import contactssila.sharedui.generated.resources.sf_error_not_found
import contactssila.sharedui.generated.resources.action_cancel
import contactssila.sharedui.generated.resources.sf_arham_offer_accept
import contactssila.sharedui.generated.resources.sf_arham_offer_title
import contactssila.sharedui.generated.resources.sf_evidence_label
import contactssila.sharedui.generated.resources.sf_group_count
import contactssila.sharedui.generated.resources.sf_loading
import contactssila.sharedui.generated.resources.sf_never_ask
import contactssila.sharedui.generated.resources.sf_not_this_category
import contactssila.sharedui.generated.resources.sf_screen_title
import contactssila.sharedui.generated.resources.sf_select_all
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشة مراجعة الفلترة الذكية (T7 · Issue #28) من موارد الترجمة الحقيقية:
 * `values/` (العربية — الافتراضية) و `values-en/` — بنمط مزوّدات T5/T6/T7. لا نص ثابت
 * في كود الواجهة (AGENTS.md)، والقوالب تُعوَّض يدوياً في `commonMain`.
 */
@Composable
fun rememberSmartFilterReviewStrings(): SmartFilterReviewStrings {
    val family = stringResource(Res.string.category_family)
    val friends = stringResource(Res.string.category_friends)
    val work = stringResource(Res.string.category_work)
    val services = stringResource(Res.string.category_services)
    val emergency = stringResource(Res.string.category_emergency)
    val uncategorized = stringResource(Res.string.category_uncategorized)

    val evidenceLabel = stringResource(Res.string.sf_evidence_label)
    val groupCount = stringResource(Res.string.sf_group_count)
    val applySelected = stringResource(Res.string.sf_apply_selected)
    val arhamOfferTitleTemplate = stringResource(Res.string.sf_arham_offer_title)
    val arhamOfferTitleLambda = { count: Int ->
        arhamOfferTitleTemplate.replaceFirst("%d", count.toString())
    }

    return ResourceSmartFilterReviewStrings(
        screenTitle = stringResource(Res.string.sf_screen_title),
        backDescription = stringResource(Res.string.sf_back_description),
        loading = stringResource(Res.string.sf_loading),
        emptyTitle = stringResource(Res.string.sf_empty_title),
        emptyMessage = stringResource(Res.string.sf_empty_message),
        confidenceHigh = stringResource(Res.string.sf_confidence_high),
        confidenceMedium = stringResource(Res.string.sf_confidence_medium),
        confidenceLow = stringResource(Res.string.sf_confidence_low),
        evidenceLabel = { token -> evidenceLabel.replaceFirst("%s", token) },
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
        groupCount = { count -> groupCount.replaceFirst("%d", count.toString()) },
        selectAll = stringResource(Res.string.sf_select_all),
        applySelected = { count -> applySelected.replaceFirst("%d", count.toString()) },
        notThisCategory = stringResource(Res.string.sf_not_this_category),
        neverAsk = stringResource(Res.string.sf_never_ask),
        errorApply = stringResource(Res.string.sf_error_apply),
        errorContactNotFound = stringResource(Res.string.sf_error_not_found),
        errorArhamLink = stringResource(Res.string.sf_error_arham_link),
        arhamOfferTitle = arhamOfferTitleLambda,
        arhamOfferAccept = stringResource(Res.string.sf_arham_offer_accept),
        cancel = stringResource(Res.string.action_cancel),
    )
}

/**
 * تنفيذ نصوص الموارد — كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا.
 * (`sf_unselect_all` مخصصة لمرايا SwiftUI — زر المجموعة هنا تناوب نقرة-ثانية يُلغي.)
 */
private data class ResourceSmartFilterReviewStrings(
    override val screenTitle: String,
    override val backDescription: String,
    override val loading: String,
    override val emptyTitle: String,
    override val emptyMessage: String,
    override val confidenceHigh: String,
    override val confidenceMedium: String,
    override val confidenceLow: String,
    override val evidenceLabel: (token: String) -> String,
    override val categoryTitle: (category: Category) -> String,
    override val groupCount: (count: Int) -> String,
    override val selectAll: String,
    override val applySelected: (count: Int) -> String,
    override val notThisCategory: String,
    override val neverAsk: String,
    override val errorApply: String,
    override val errorContactNotFound: String,
    override val errorArhamLink: String,
    override val arhamOfferTitle: (count: Int) -> String,
    override val arhamOfferAccept: String,
    override val cancel: String,
) : SmartFilterReviewStrings
