package app.sila.feature.contacts.presentation

import app.sila.core.model.Category

/**
 * نصوص شاشة الإضافة/التعديل (T6 · Issue #27 §ب) — عقد بلا Compose: يُموَّل في `sharedUI`
 * من موارد الترجمة بنمط [ContactsStrings] (لا نصوص ثابتة في الكود — AGENTS.md).
 */
interface ContactEditStrings {
    // شريط التطبيق
    val newContactTitle: String
    val editContactTitle: String
    val save: String
    val closeDescription: String
    val saving: String

    // الخروج بلا حفظ
    val discardConfirmTitle: String
    val discardConfirmMessage: String
    val discard: String
    val keepEditing: String

    // الصورة
    val addPhoto: String
    val changePhoto: String
    val removePhoto: String
    val photoCamera: String
    val photoGallery: String

    // التصنيف
    val categoryLabel: String
    val categoryTitle: (category: Category) -> String

    // الحقول الأساسية
    val firstNameLabel: String
    val lastNameLabel: String
    val phoneLabel: String
    val countryKeyLabel: String

    // «إظهار المزيد» والمتقدم
    val showMore: String
    val showLess: String
    val prefixLabel: String
    val middleNameLabel: String
    val suffixLabel: String
    val nicknameLabel: String
    val fileAsLabel: String
    val phoneticLabel: String

    // القوائم المتعددة
    val addPhone: String
    val addEmail: String
    val addWebsite: String
    val addDate: String
    val addAddress: String
    val addCustomField: String
    val removeRowDescription: String

    // التسميات
    val emailLabel: String
    val websiteLabel: String
    val companyLabel: String
    val departmentLabel: String
    val jobTitleLabel: String
    val notesLabel: String
    val datesLabel: String
    val addressesLabel: String
    val customFieldsLabel: String
    val accountLabel: String
    val accountDevice: String

    // أنواع الهاتف/البريد (+ «مخصص»)
    val typeMobile: String
    val typeHome: String
    val typeWork: String
    val typeOther: String
    val typeCustom: String
    val customTypePlaceholder: String

    // أنواع التواريخ
    val dateBirthday: String
    val dateAnniversary: String
    val dateCustomDate: String
    val dateMonth: String
    val dateDay: String
    val dateYear: String

    // العنوان
    val streetLabel: String
    val cityLabel: String
    val regionLabel: String
    val postalCodeLabel: String
    val countryLabel: String

    // الحقول المخصصة
    val customFieldLabelLabel: String
    val customFieldValueLabel: String

    // التحقق والتحذيرات (غير مانعة)
    val validationNameOrPhone: String
    val duplicateWarning: (existingName: String) -> String
    val invalidPhoneWarning: String

    // Sheet التصنيف عند الحفظ (AC)
    val categoryPromptTitle: String
    val categoryPromptLater: String

    // تلميح أرحام (J-4)
    val arhamBirthdayHint: String

    // أخطاء
    val saveError: String
    val notFoundMessage: String
}
