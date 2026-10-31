package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.Category
import app.sila.feature.contacts.presentation.ContactsStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.action_call
import contactssila.sharedui.generated.resources.action_cancel
import contactssila.sharedui.generated.resources.action_confirm
import contactssila.sharedui.generated.resources.action_delete
import contactssila.sharedui.generated.resources.action_message
import contactssila.sharedui.generated.resources.action_share
import contactssila.sharedui.generated.resources.category_all
import contactssila.sharedui.generated.resources.category_emergency
import contactssila.sharedui.generated.resources.category_family
import contactssila.sharedui.generated.resources.category_friends
import contactssila.sharedui.generated.resources.category_services
import contactssila.sharedui.generated.resources.category_uncategorized
import contactssila.sharedui.generated.resources.category_work
import contactssila.sharedui.generated.resources.contacts_account_all
import contactssila.sharedui.generated.resources.contacts_account_device
import contactssila.sharedui.generated.resources.contacts_account_google
import contactssila.sharedui.generated.resources.contacts_account_label
import contactssila.sharedui.generated.resources.contacts_account_other
import contactssila.sharedui.generated.resources.contacts_bulk_categorize
import contactssila.sharedui.generated.resources.contacts_bulk_favorite
import contactssila.sharedui.generated.resources.contacts_bulk_unfavorite
import contactssila.sharedui.generated.resources.contacts_delete_confirm_body
import contactssila.sharedui.generated.resources.contacts_delete_confirm_title
import contactssila.sharedui.generated.resources.contacts_done
import contactssila.sharedui.generated.resources.contacts_empty_body
import contactssila.sharedui.generated.resources.contacts_empty_search_body
import contactssila.sharedui.generated.resources.contacts_empty_search_title
import contactssila.sharedui.generated.resources.contacts_empty_title
import contactssila.sharedui.generated.resources.contacts_error_body
import contactssila.sharedui.generated.resources.contacts_error_title
import contactssila.sharedui.generated.resources.contacts_loading
import contactssila.sharedui.generated.resources.contacts_number_picker_title
import contactssila.sharedui.generated.resources.contacts_open_settings
import contactssila.sharedui.generated.resources.contacts_permission_denied_body
import contactssila.sharedui.generated.resources.contacts_permission_denied_title
import contactssila.sharedui.generated.resources.contacts_retry
import contactssila.sharedui.generated.resources.contacts_search_hint
import contactssila.sharedui.generated.resources.contacts_select_hint
import contactssila.sharedui.generated.resources.contacts_selection_title
import contactssila.sharedui.generated.resources.contacts_suggestion_dismiss
import contactssila.sharedui.generated.resources.contacts_suggestion_duplicates
import contactssila.sharedui.generated.resources.contacts_suggestion_smart_filter
import contactssila.sharedui.generated.resources.contacts_title
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشة جهات الاتصال (Issue #24 — T3) من موارد الترجمة الحقيقية:
 * `values/` (العربية — الافتراضية) و `values-en/`. لا نص ثابت في كود الواجهة
 * إطلاقاً (AGENTS.md). القوالب ذات العدد تستخدم `%d` وتُعوَّض يدوياً في
 * `commonMain` (لا `String.format` — غير متاح في الكود المشترك).
 */
@Composable
fun rememberContactsStrings(): ContactsStrings {
    val duplicatesTemplate = stringResource(Res.string.contacts_suggestion_duplicates)
    val smartFilterTemplate = stringResource(Res.string.contacts_suggestion_smart_filter)
    val selectionTemplate = stringResource(Res.string.contacts_selection_title)
    val deleteBodyTemplate = stringResource(Res.string.contacts_delete_confirm_body)

    val family = stringResource(Res.string.category_family)
    val friends = stringResource(Res.string.category_friends)
    val work = stringResource(Res.string.category_work)
    val services = stringResource(Res.string.category_services)
    val emergency = stringResource(Res.string.category_emergency)
    val uncategorized = stringResource(Res.string.category_uncategorized)

    return ResourceContactsStrings(
        screenTitle = stringResource(Res.string.contacts_title),
        categoryAll = stringResource(Res.string.category_all),
        searchPlaceholder = stringResource(Res.string.contacts_search_hint),
        loading = stringResource(Res.string.contacts_loading),
        emptyContactsTitle = stringResource(Res.string.contacts_empty_title),
        emptyContactsMessage = stringResource(Res.string.contacts_empty_body),
        emptySearchTitle = stringResource(Res.string.contacts_empty_search_title),
        emptySearchMessage = stringResource(Res.string.contacts_empty_search_body),
        permissionDeniedTitle = stringResource(Res.string.contacts_permission_denied_title),
        permissionDeniedMessage = stringResource(Res.string.contacts_permission_denied_body),
        openSettings = stringResource(Res.string.contacts_open_settings),
        errorTitle = stringResource(Res.string.contacts_error_title),
        errorMessage = stringResource(Res.string.contacts_error_body),
        retry = stringResource(Res.string.contacts_retry),
        suggestionDuplicates = { count -> duplicatesTemplate.replace("%d", count.toString()) },
        suggestionSmartFilter = { count -> smartFilterTemplate.replace("%d", count.toString()) },
        suggestionDismiss = stringResource(Res.string.contacts_suggestion_dismiss),
        selectionTitle = { count -> selectionTemplate.replace("%d", count.toString()) },
        bulkCategorize = stringResource(Res.string.contacts_bulk_categorize),
        bulkFavorite = stringResource(Res.string.contacts_bulk_favorite),
        bulkUnfavorite = stringResource(Res.string.contacts_bulk_unfavorite),
        bulkShare = stringResource(Res.string.action_share),
        bulkDelete = stringResource(Res.string.action_delete),
        selectionDone = stringResource(Res.string.contacts_done),
        deleteConfirmationTitle = stringResource(Res.string.contacts_delete_confirm_title),
        deleteConfirmationMessage = { count -> deleteBodyTemplate.replace("%d", count.toString()) },
        confirmDelete = stringResource(Res.string.action_confirm),
        cancel = stringResource(Res.string.action_cancel),
        swipeCall = stringResource(Res.string.action_call),
        swipeMessage = stringResource(Res.string.action_message),
        callRow = stringResource(Res.string.action_call),
        selectContact = stringResource(Res.string.contacts_select_hint),
        numberPickerTitle = stringResource(Res.string.contacts_number_picker_title),
        accountFilterLabel = stringResource(Res.string.contacts_account_label),
        accountFilterAll = stringResource(Res.string.contacts_account_all),
        accountFilterDevice = stringResource(Res.string.contacts_account_device),
        accountFilterGoogle = stringResource(Res.string.contacts_account_google),
        accountFilterOther = stringResource(Res.string.contacts_account_other),
        categoryTitle = { category ->
            when (category) {
                Category.FAMILY -> family
                Category.FRIENDS -> friends
                Category.WORK -> work
                Category.SERVICES -> services
                Category.EMERGENCY -> emergency
                Category.UNCATEGORIZED -> uncategorized
            }
        }
    )
}

/**
 * تنفيذ نصوص الموارد (كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا).
 */
private data class ResourceContactsStrings(
    override val screenTitle: String,
    override val categoryAll: String,
    override val searchPlaceholder: String,
    override val loading: String,
    override val emptyContactsTitle: String,
    override val emptyContactsMessage: String,
    override val emptySearchTitle: String,
    override val emptySearchMessage: String,
    override val permissionDeniedTitle: String,
    override val permissionDeniedMessage: String,
    override val openSettings: String,
    override val errorTitle: String,
    override val errorMessage: String,
    override val retry: String,
    override val suggestionDuplicates: (count: Int) -> String,
    override val suggestionSmartFilter: (count: Int) -> String,
    override val suggestionDismiss: String,
    override val selectionTitle: (count: Int) -> String,
    override val bulkCategorize: String,
    override val bulkFavorite: String,
    override val bulkUnfavorite: String,
    override val bulkShare: String,
    override val bulkDelete: String,
    override val selectionDone: String,
    override val deleteConfirmationTitle: String,
    override val deleteConfirmationMessage: (count: Int) -> String,
    override val confirmDelete: String,
    override val cancel: String,
    override val swipeCall: String,
    override val swipeMessage: String,
    override val callRow: String,
    override val selectContact: String,
    override val numberPickerTitle: String,
    override val accountFilterLabel: String,
    override val accountFilterAll: String,
    override val accountFilterDevice: String,
    override val accountFilterGoogle: String,
    override val accountFilterOther: String,
    override val categoryTitle: (category: Category) -> String
) : ContactsStrings
