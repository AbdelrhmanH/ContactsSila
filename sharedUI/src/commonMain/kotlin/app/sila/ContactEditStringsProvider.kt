package app.sila

import androidx.compose.runtime.Composable
import app.sila.core.model.Category
import app.sila.feature.contacts.presentation.ContactEditStrings
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.category_emergency
import contactssila.sharedui.generated.resources.category_family
import contactssila.sharedui.generated.resources.category_friends
import contactssila.sharedui.generated.resources.category_services
import contactssila.sharedui.generated.resources.category_uncategorized
import contactssila.sharedui.generated.resources.category_work
import contactssila.sharedui.generated.resources.details_date_anniversary
import contactssila.sharedui.generated.resources.details_date_birthday
import contactssila.sharedui.generated.resources.details_date_custom
import contactssila.sharedui.generated.resources.details_not_found
import contactssila.sharedui.generated.resources.details_type_home
import contactssila.sharedui.generated.resources.details_type_mobile
import contactssila.sharedui.generated.resources.details_type_other
import contactssila.sharedui.generated.resources.details_type_work
import contactssila.sharedui.generated.resources.details_email_personal
import contactssila.sharedui.generated.resources.edit_account_device
import contactssila.sharedui.generated.resources.edit_account_label
import contactssila.sharedui.generated.resources.edit_add_address
import contactssila.sharedui.generated.resources.edit_add_custom_field
import contactssila.sharedui.generated.resources.edit_add_date
import contactssila.sharedui.generated.resources.edit_add_email
import contactssila.sharedui.generated.resources.edit_add_photo
import contactssila.sharedui.generated.resources.edit_add_phone
import contactssila.sharedui.generated.resources.edit_add_website
import contactssila.sharedui.generated.resources.edit_addresses_section
import contactssila.sharedui.generated.resources.edit_arham_birthday_hint
import contactssila.sharedui.generated.resources.edit_category_label
import contactssila.sharedui.generated.resources.edit_category_prompt_later
import contactssila.sharedui.generated.resources.edit_category_prompt_title
import contactssila.sharedui.generated.resources.edit_change_photo
import contactssila.sharedui.generated.resources.edit_city
import contactssila.sharedui.generated.resources.edit_close_description
import contactssila.sharedui.generated.resources.edit_company
import contactssila.sharedui.generated.resources.edit_country
import contactssila.sharedui.generated.resources.edit_country_key
import contactssila.sharedui.generated.resources.edit_custom_field_label
import contactssila.sharedui.generated.resources.edit_custom_field_value
import contactssila.sharedui.generated.resources.edit_custom_fields_section
import contactssila.sharedui.generated.resources.edit_custom_type_placeholder
import contactssila.sharedui.generated.resources.edit_dates_section
import contactssila.sharedui.generated.resources.edit_day
import contactssila.sharedui.generated.resources.edit_department
import contactssila.sharedui.generated.resources.edit_discard
import contactssila.sharedui.generated.resources.edit_discard_message
import contactssila.sharedui.generated.resources.edit_discard_title
import contactssila.sharedui.generated.resources.edit_duplicate_warning
import contactssila.sharedui.generated.resources.edit_edit_title
import contactssila.sharedui.generated.resources.edit_email_label
import contactssila.sharedui.generated.resources.edit_file_as
import contactssila.sharedui.generated.resources.edit_first_name
import contactssila.sharedui.generated.resources.edit_invalid_phone
import contactssila.sharedui.generated.resources.edit_job_title
import contactssila.sharedui.generated.resources.edit_keep_editing
import contactssila.sharedui.generated.resources.edit_last_name
import contactssila.sharedui.generated.resources.edit_middle_name
import contactssila.sharedui.generated.resources.edit_month
import contactssila.sharedui.generated.resources.edit_new_title
import contactssila.sharedui.generated.resources.edit_notes
import contactssila.sharedui.generated.resources.edit_nickname
import contactssila.sharedui.generated.resources.edit_phone_label
import contactssila.sharedui.generated.resources.edit_phonetic
import contactssila.sharedui.generated.resources.edit_photo_camera
import contactssila.sharedui.generated.resources.edit_photo_gallery
import contactssila.sharedui.generated.resources.edit_postal_code
import contactssila.sharedui.generated.resources.edit_prefix
import contactssila.sharedui.generated.resources.edit_region
import contactssila.sharedui.generated.resources.edit_remove_photo
import contactssila.sharedui.generated.resources.edit_remove_row
import contactssila.sharedui.generated.resources.edit_save
import contactssila.sharedui.generated.resources.edit_save_error
import contactssila.sharedui.generated.resources.edit_saving
import contactssila.sharedui.generated.resources.edit_show_less
import contactssila.sharedui.generated.resources.edit_show_more
import contactssila.sharedui.generated.resources.edit_street
import contactssila.sharedui.generated.resources.edit_suffix
import contactssila.sharedui.generated.resources.edit_type_custom
import contactssila.sharedui.generated.resources.edit_validation_name_or_phone
import contactssila.sharedui.generated.resources.edit_website_label
import contactssila.sharedui.generated.resources.edit_year
import org.jetbrains.compose.resources.stringResource

/**
 * مزوّد نصوص شاشة الإضافة/التعديل (T6 · Issue #27) من موارد الترجمة الحقيقية —
 * نمط [rememberContactsStrings]. مفتاح الدولة الافتراضي (`+20` مصر) قيمة نموذجية
 * وليست نصاً معروضاً — يبقى في النطاق لا في الموارد (راجع VM).
 */
@Composable
fun rememberContactEditStrings(): ContactEditStrings {
    val family = stringResource(Res.string.category_family)
    val friends = stringResource(Res.string.category_friends)
    val work = stringResource(Res.string.category_work)
    val services = stringResource(Res.string.category_services)
    val emergency = stringResource(Res.string.category_emergency)
    val uncategorized = stringResource(Res.string.category_uncategorized)
    val duplicateTemplate = stringResource(Res.string.edit_duplicate_warning)

    return ResourceContactEditStrings(
        newContactTitle = stringResource(Res.string.edit_new_title),
        editContactTitle = stringResource(Res.string.edit_edit_title),
        save = stringResource(Res.string.edit_save),
        closeDescription = stringResource(Res.string.edit_close_description),
        saving = stringResource(Res.string.edit_saving),
        discardConfirmTitle = stringResource(Res.string.edit_discard_title),
        discardConfirmMessage = stringResource(Res.string.edit_discard_message),
        discard = stringResource(Res.string.edit_discard),
        keepEditing = stringResource(Res.string.edit_keep_editing),
        addPhoto = stringResource(Res.string.edit_add_photo),
        changePhoto = stringResource(Res.string.edit_change_photo),
        removePhoto = stringResource(Res.string.edit_remove_photo),
        photoCamera = stringResource(Res.string.edit_photo_camera),
        photoGallery = stringResource(Res.string.edit_photo_gallery),
        categoryLabel = stringResource(Res.string.edit_category_label),
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
        firstNameLabel = stringResource(Res.string.edit_first_name),
        lastNameLabel = stringResource(Res.string.edit_last_name),
        phoneLabel = stringResource(Res.string.edit_phone_label),
        countryKeyLabel = stringResource(Res.string.edit_country_key),
        showMore = stringResource(Res.string.edit_show_more),
        showLess = stringResource(Res.string.edit_show_less),
        prefixLabel = stringResource(Res.string.edit_prefix),
        middleNameLabel = stringResource(Res.string.edit_middle_name),
        suffixLabel = stringResource(Res.string.edit_suffix),
        nicknameLabel = stringResource(Res.string.edit_nickname),
        fileAsLabel = stringResource(Res.string.edit_file_as),
        phoneticLabel = stringResource(Res.string.edit_phonetic),
        addPhone = stringResource(Res.string.edit_add_phone),
        addEmail = stringResource(Res.string.edit_add_email),
        addWebsite = stringResource(Res.string.edit_add_website),
        addDate = stringResource(Res.string.edit_add_date),
        addAddress = stringResource(Res.string.edit_add_address),
        addCustomField = stringResource(Res.string.edit_add_custom_field),
        removeRowDescription = stringResource(Res.string.edit_remove_row),
        emailLabel = stringResource(Res.string.edit_email_label),
        websiteLabel = stringResource(Res.string.edit_website_label),
        companyLabel = stringResource(Res.string.edit_company),
        departmentLabel = stringResource(Res.string.edit_department),
        jobTitleLabel = stringResource(Res.string.edit_job_title),
        notesLabel = stringResource(Res.string.edit_notes),
        datesLabel = stringResource(Res.string.edit_dates_section),
        addressesLabel = stringResource(Res.string.edit_addresses_section),
        customFieldsLabel = stringResource(Res.string.edit_custom_fields_section),
        accountLabel = stringResource(Res.string.edit_account_label),
        accountDevice = stringResource(Res.string.edit_account_device),
        typeMobile = stringResource(Res.string.details_type_mobile),
        typeHome = stringResource(Res.string.details_type_home),
        typeWork = stringResource(Res.string.details_type_work),
        typeOther = stringResource(Res.string.details_type_other),
        typeCustom = stringResource(Res.string.edit_type_custom),
        customTypePlaceholder = stringResource(Res.string.edit_custom_type_placeholder),
        dateBirthday = stringResource(Res.string.details_date_birthday),
        dateAnniversary = stringResource(Res.string.details_date_anniversary),
        dateCustomDate = stringResource(Res.string.details_date_custom),
        dateMonth = stringResource(Res.string.edit_month),
        dateDay = stringResource(Res.string.edit_day),
        dateYear = stringResource(Res.string.edit_year),
        streetLabel = stringResource(Res.string.edit_street),
        cityLabel = stringResource(Res.string.edit_city),
        regionLabel = stringResource(Res.string.edit_region),
        postalCodeLabel = stringResource(Res.string.edit_postal_code),
        countryLabel = stringResource(Res.string.edit_country),
        customFieldLabelLabel = stringResource(Res.string.edit_custom_field_label),
        customFieldValueLabel = stringResource(Res.string.edit_custom_field_value),
        validationNameOrPhone = stringResource(Res.string.edit_validation_name_or_phone),
        duplicateWarning = { existingName -> duplicateTemplate.replace("%s", existingName) },
        invalidPhoneWarning = stringResource(Res.string.edit_invalid_phone),
        categoryPromptTitle = stringResource(Res.string.edit_category_prompt_title),
        categoryPromptLater = stringResource(Res.string.edit_category_prompt_later),
        arhamBirthdayHint = stringResource(Res.string.edit_arham_birthday_hint),
        saveError = stringResource(Res.string.edit_save_error),
        notFoundMessage = stringResource(Res.string.details_not_found)
    )
}

/** تنفيذ نصوص الموارد — كل القيم أتت من موارد الترجمة ولا قيم متغايرة هنا. */
private data class ResourceContactEditStrings(
    override val newContactTitle: String,
    override val editContactTitle: String,
    override val save: String,
    override val closeDescription: String,
    override val saving: String,
    override val discardConfirmTitle: String,
    override val discardConfirmMessage: String,
    override val discard: String,
    override val keepEditing: String,
    override val addPhoto: String,
    override val changePhoto: String,
    override val removePhoto: String,
    override val photoCamera: String,
    override val photoGallery: String,
    override val categoryLabel: String,
    override val categoryTitle: (category: Category) -> String,
    override val firstNameLabel: String,
    override val lastNameLabel: String,
    override val phoneLabel: String,
    override val countryKeyLabel: String,
    override val showMore: String,
    override val showLess: String,
    override val prefixLabel: String,
    override val middleNameLabel: String,
    override val suffixLabel: String,
    override val nicknameLabel: String,
    override val fileAsLabel: String,
    override val phoneticLabel: String,
    override val addPhone: String,
    override val addEmail: String,
    override val addWebsite: String,
    override val addDate: String,
    override val addAddress: String,
    override val addCustomField: String,
    override val removeRowDescription: String,
    override val emailLabel: String,
    override val websiteLabel: String,
    override val companyLabel: String,
    override val departmentLabel: String,
    override val jobTitleLabel: String,
    override val notesLabel: String,
    override val datesLabel: String,
    override val addressesLabel: String,
    override val customFieldsLabel: String,
    override val accountLabel: String,
    override val accountDevice: String,
    override val typeMobile: String,
    override val typeHome: String,
    override val typeWork: String,
    override val typeOther: String,
    override val typeCustom: String,
    override val customTypePlaceholder: String,
    override val dateBirthday: String,
    override val dateAnniversary: String,
    override val dateCustomDate: String,
    override val dateMonth: String,
    override val dateDay: String,
    override val dateYear: String,
    override val streetLabel: String,
    override val cityLabel: String,
    override val regionLabel: String,
    override val postalCodeLabel: String,
    override val countryLabel: String,
    override val customFieldLabelLabel: String,
    override val customFieldValueLabel: String,
    override val validationNameOrPhone: String,
    override val duplicateWarning: (existingName: String) -> String,
    override val invalidPhoneWarning: String,
    override val categoryPromptTitle: String,
    override val categoryPromptLater: String,
    override val arhamBirthdayHint: String,
    override val saveError: String,
    override val notFoundMessage: String
) : ContactEditStrings
