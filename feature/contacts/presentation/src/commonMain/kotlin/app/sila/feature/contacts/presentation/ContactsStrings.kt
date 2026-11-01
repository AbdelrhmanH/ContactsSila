package app.sila.feature.contacts.presentation

import app.sila.core.model.Category

/**
 * نصوص واجهة جهات الاتصال (Issue #24) — عقد بلا Compose: تُموَّل في `sharedUI` من
 * موارد الترجمة (عربي افتراضي + إنجليزي) بنمط `OnboardingStrings` المجرَّب في T5.
 * لا نصوص ثابتة في الكود (AGENTS.md).
 */
interface ContactsStrings {
    val screenTitle: String
    val categoryAll: String
    val searchPlaceholder: String
    val loading: String
    val emptyContactsTitle: String
    val emptyContactsMessage: String
    val emptySearchTitle: String
    val emptySearchMessage: String
    val permissionDeniedTitle: String
    val permissionDeniedMessage: String
    val openSettings: String
    val errorTitle: String
    val errorMessage: String
    val retry: String

    val suggestionDuplicates: (count: Int) -> String
    val suggestionSmartFilter: (count: Int) -> String
    val suggestionDismiss: String

    val selectionTitle: (count: Int) -> String
    val bulkCategorize: String
    val bulkFavorite: String
    val bulkUnfavorite: String
    val bulkShare: String
    val bulkDelete: String
    val selectionDone: String

    val deleteConfirmationTitle: String
    val deleteConfirmationMessage: (count: Int) -> String
    val confirmDelete: String
    val cancel: String

    val swipeCall: String
    val swipeMessage: String
    val callRow: String
    val selectContact: String

    val numberPickerTitle: String

    val accountFilterLabel: String
    val accountFilterAll: String
    val accountFilterDevice: String
    val accountFilterGoogle: String
    val accountFilterOther: String

    val categoryTitle: (category: Category) -> String
}
