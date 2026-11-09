package app.sila.feature.settings.presentation

import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.Relation
import app.sila.feature.settings.domain.BackupErrorKind
import app.sila.feature.settings.domain.UnmatchedBackupEntry

/**
 * النصوص المترجمة لشاشة الإعدادات (T9 — Issue #30): مركزية النصوص لمنع النصوص
 * الثابتة داخل الواجهة — كل الاستهلاك عبر الحزم [arabic]/[english] في الـ Companion،
 * والجدولان [ArabicTable]/[EnglishTable] بيانات ثابتة تُعرف في خصائص عليا —
 * أي خارج نطاق قاعدة LongMethod حيث إنها بادئات قيم لا دوال.
 *
 * S2-17 (تذكرة #67): كان الجدولان دالتي `arabic()`/`english()` بحجم ~93 سطراً
 * لكل منهما فوق حد LongMethod (60) — صار الجدولان خاصيتين والدالان تمريرِيّن رفيعَي
 * أسطر في Companion، بلا أي مساس بالمستهلكين ولا بمحتوى النصوص.
 */
data class SettingsStrings(
    val title: String,
    val close: String,
    val sectionAppearance: String,
    val theme: String,
    val themeLight: String,
    val themeDark: String,
    val themeSystem: String,
    val language: String,
    val digits: String,
    val digitsArabic: String,
    val digitsHindi: String,
    val nameOrder: String,
    val nameOrderFirstLast: String,
    val nameOrderLastFirst: String,
    val sectionGeneral: String,
    val defaultTab: String,
    val tabContacts: String,
    val tabServices: String,
    val tabArham: String,
    val swipeStart: String,
    val swipeEnd: String,
    val swipeCall: String,
    val swipeMessage: String,
    val swipeNone: String,
    val simSlot: String,
    val sectionArham: String,
    val arhamNotifications: String,
    val arhamTime: String,
    val arhamMaxPerDay: String,
    val quietHours: String,
    val quietHoursPickTitle: String,
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val save: String,
    val cancel: String,
    val sectionOrganization: String,
    val smartFilter: String,
    val duplicateScan: String,
    val customKeywords: String,
    val addKeyword: String,
    val sectionRingtones: String,
    val ringtonePriority: String,
    val customRingtone: String,
    val systemDefaultRingtone: String,
    val chooseRingtone: String,
    val ringtoneExplain: String,
    val resetDefault: String,
    val noRingtonesAvailable: String,
    val backupErrorBlankPassword: String,
    val backupErrorDecryptionFailed: String,
    val backupErrorNotConfigured: String,
    val backupErrorGeneric: String,
    val chooseBackupFile: String,
    val importFileTooLarge: String,
    val backupSaveFailed: String,
    val sectionBackup: String,
    val localPrivacy: String,
    val localPrivacyValue: String,
    val exportBackup: String,
    val restoreBackup: String,
    val crashReports: String,
    val deleteAccount: String,
    val deleteAccountValue: String,
    val exportDialogTitle: String,
    val exportDialogDesc: String,
    val passwordLabel: String,
    val exportButton: String,
    val encrypting: String,
    val restoreDialogTitle: String,
    val restoreDialogDesc: String,
    val previewButton: String,
    val verifying: String,
    val previewDialogTitle: String,
    val restoreModeMerge: String,
    val restoreModeMergeDesc: String,
    val restoreModeReplace: String,
    val restoreModeReplaceDesc: String,
    val restorePrefsCheckbox: String,
    val startRestore: String,
    val aboutSection: String,
    val versionTitle: String,
    val versionValue: String,
    val privacyTerms: String,
    val rateApp: String,
    val linkManualButton: String,
    val linkSearchHint: String,
    val linkNoCandidates: String,
    val linkFailedLabel: String,
    val linkAllDone: String,
    val resultDone: String,
    val restoreResultTitle: String
) {
    companion object {
        fun arabic(): SettingsStrings = ArabicTable
        fun english(): SettingsStrings = EnglishTable
    }
}

/** جدول النصوص العربية — بيانات ثابتة كاملة (خاصية عليا لا دالة). */
private val ArabicTable: SettingsStrings = SettingsStrings(
    title = "الإعدادات والتفضيلات",
    close = "إغلاق الإعدادات",
    sectionAppearance = "المظهر واللغة",
    theme = "السمة المظهرية",
    themeLight = "فاتح ☀️",
    themeDark = "داكن 🌙",
    themeSystem = "تلقائي (حسب النظام) ⚙️",
    language = "لغة التطبيق",
    digits = "كتابة الأرقام",
    digitsArabic = "أرقام عربية غربية (1 2 3)",
    digitsHindi = "أرقام عربية مشرقية (١ ٢ ٣)",
    nameOrder = "ترتيب عرض الأسماء",
    nameOrderFirstLast = "الاسم الأول أولاً",
    nameOrderLastFirst = "اسم العائلة أولاً",
    sectionGeneral = "عام وسلوك التطبيق",
    defaultTab = "التبويب الافتراضي عند الفتح",
    tabContacts = "جهات الاتصال 👥",
    tabServices = "دليل الخدمات 🛠️",
    tabArham = "أرحام ❤️",
    swipeStart = "سحب البداية",
    swipeEnd = "سحب النهاية",
    swipeCall = "اتصال 📞",
    swipeMessage = "رسالة 💬",
    swipeNone = "معطل ✕",
    simSlot = "شريحة الاتصال المفضلة",
    sectionArham = "صلة الرحم (أرحام)",
    arhamNotifications = "تفعيل تنبيهات صلة الرحم",
    arhamTime = "وقت التذكير اليومي",
    arhamMaxPerDay = "الحد الأقصى للتنبيهات يومياً",
    quietHours = "الساعات الهادئة (حظر التنبيهات)",
    quietHoursPickTitle = "تحديد الساعات الهادئة",
    quietHoursStart = "بداية الهدوء",
    quietHoursEnd = "نهاية الهدوء",
    save = "حفظ",
    cancel = "إلغاء",
    sectionOrganization = "التنظيم والذكاء الاصطناعي المحلي",
    smartFilter = "الفلترة الذكية التلقائية",
    duplicateScan = "فحص التكرارات بالخلفية عند التشغيل",
    customKeywords = "الكلمات المفتاحية المخصصة للتصنيف",
    addKeyword = "إضافة",
    sectionRingtones = "نغمات التصنيفات",
    ringtonePriority = "الأولوية: نغمة الشخص > نغمة التصنيف > نغمة النظام",
    customRingtone = "مخصصة ✓",
    systemDefaultRingtone = "نغمة النظام الافتراضية",
    chooseRingtone = "اختر نغمة",
    ringtoneExplain = "سيتم تطبيق هذه النغمة تلقائياً على جهات الاتصال في هذا التصنيف التي ليس لها نغمة فردية.",
    resetDefault = "إعادة تعيين لنغمة النظام الافتراضية",
    noRingtonesAvailable = "لا توجد نغمات متاحة في هذا الجهاز. أضف نغمات من إعدادات النظام أو استخدم زر إعادة التعيين.",
    backupErrorBlankPassword = "كلمة المرور فارغة — أدخل كلمة مرور ثم أعد المحاولة.",
    backupErrorDecryptionFailed = "تعذر فك التشفير — تأكد من كلمة المرور وأن الملف نسخة صِلَة غير تالفة.",
    backupErrorNotConfigured = "خدمة النسخ الاحتياطي غير مهيأة في هذه الحالة.",
    backupErrorGeneric = "فشلت العملية — حاول مجدداً، وإذا تكرر ذلك أعد المحاولة بنسخة أخرى.",
    chooseBackupFile = "اختيار ملف نسخة من الجهاز",
    importFileTooLarge = "الملف أكبر من الحد الأقصى للنسخة (16MB) — اختر نسخة أصغر.",
    backupSaveFailed = "تعذر حفظ الملف — تحقق من المسار وحاول مرة أخرى.",
    sectionBackup = "الخصوصية والنسخ الاحتياطي المشفر",
    localPrivacy = "ماذا نعرف عنك؟ (الخصوصية المحلية)",
    localPrivacyValue = "0 بيانات مرفوعة (محلي 100%)",
    exportBackup = "تصدير نسخة مشفرة (.sila.backup)",
    restoreBackup = "استعادة نسخة احتياطية",
    crashReports = "إرسال تقارير الأعطال مجهولة المصدر",
    deleteAccount = "حذف البيانات والبدء من جديد",
    deleteAccountValue = "محلي فقط (لا سحابة)",
    exportDialogTitle = "تصدير نسخة احتياطية مشفرة",
    exportDialogDesc = "سيتم تشفير التصنيفات وبيانات أرحام والوسوم والتفضيلات بـ AES-256-GCM. أدخل كلمة مرور قوية:",
    passwordLabel = "كلمة المرور",
    exportButton = "تصدير مشفر",
    encrypting = "جاري التشفير...",
    restoreDialogTitle = "استعادة نسخة احتياطية",
    restoreDialogDesc = "أدخل كلمة المرور لمعاينة وفك تشفير النسخة:",
    previewButton = "معاينة النسخة",
    verifying = "جاري الفحص...",
    previewDialogTitle = "معاينة النسخة الاحتياطية",
    restoreModeMerge = "دمج مع البيانات الحالية",
    restoreModeMergeDesc = "تحديث التصنيفات وصلة الرحم والوسوم دون حذف القائم",
    restoreModeReplace = "استبدال البيانات الحالية",
    restoreModeReplaceDesc = "إعادة تعيين التصنيفات والوسوم القديمة وتطبيق محتوى النسخة",
    restorePrefsCheckbox = "استعادة تفضيلات وإعدادات التطبيق أيضاً",
    startRestore = "بدء الاستعادة",
    aboutSection = "حول صِلَة",
    versionTitle = "رقم الإصدار",
    versionValue = "1.0.0 (Phase 1 Local MVP)",
    privacyTerms = "سياسة الخصوصية والشروط",
    rateApp = "تقييم التطبيق والدعم",
    linkManualButton = "ربط يدوي",
    linkSearchHint = "ابحث بالاسم أو الرقم (حرفان على الأقل)",
    linkNoCandidates = "لا توجد جهات مطابقة",
    linkFailedLabel = "تعذر الربط — جهة الهدف غير موجودة",
    linkAllDone = "كل السجلات غير المطابقة رُبطت بنجاح ✓",
    resultDone = "تم",
    restoreResultTitle = "نتيجة الاستعادة"
)

/** جدول النصوص الإنجليزية — بيانات ثابتة كاملة (خاصية عليا لا دالة). */
private val EnglishTable: SettingsStrings = SettingsStrings(
    title = "Settings & Preferences",
    close = "Close Settings",
    sectionAppearance = "Appearance & Language",
    theme = "Theme Mode",
    themeLight = "Light ☀️",
    themeDark = "Dark 🌙",
    themeSystem = "System Default ⚙️",
    language = "Language",
    digits = "Digits Format",
    digitsArabic = "Western Arabic (1 2 3)",
    digitsHindi = "Eastern Arabic (١ ٢ ٣)",
    nameOrder = "Name Display Order",
    nameOrderFirstLast = "First Name, Last Name",
    nameOrderLastFirst = "Last Name, First Name",
    sectionGeneral = "General & Behavior",
    defaultTab = "Default Startup Tab",
    tabContacts = "Contacts 👥",
    tabServices = "Services 🛠️",
    tabArham = "Arham ❤️",
    swipeStart = "Start Swipe",
    swipeEnd = "End Swipe",
    swipeCall = "Call 📞",
    swipeMessage = "Message 💬",
    swipeNone = "Disabled ✕",
    simSlot = "Default SIM Slot",
    sectionArham = "Kinship (Arham)",
    arhamNotifications = "Kinship Reminders",
    arhamTime = "Daily Reminder Time",
    arhamMaxPerDay = "Max Reminders Per Day",
    quietHours = "Quiet Hours (Do Not Disturb)",
    quietHoursPickTitle = "Set Quiet Hours",
    quietHoursStart = "Quiet Start",
    quietHoursEnd = "Quiet End",
    save = "Save",
    cancel = "Cancel",
    sectionOrganization = "Organization & Local AI",
    smartFilter = "Smart Filter",
    duplicateScan = "Startup Duplicate Scan",
    customKeywords = "Custom Category Keywords",
    addKeyword = "Add",
    sectionRingtones = "Category Ringtones",
    ringtonePriority = "Priority: Contact Ringtone > Category Ringtone > System Default",
    customRingtone = "Custom ✓",
    systemDefaultRingtone = "System Default",
    chooseRingtone = "Select Ringtone",
    ringtoneExplain = "This ringtone will be assigned to category contacts without individual custom ringtones.",
    resetDefault = "Reset to System Default",
    noRingtonesAvailable = "No ringtones available on this device. Add tones in system settings or use Reset to System Default.",
    backupErrorBlankPassword = "Password is empty — enter a password and try again.",
    backupErrorDecryptionFailed = "Decryption failed — check the password and that the file is an intact Sila backup.",
    backupErrorNotConfigured = "Backup service is not configured in this state.",
    backupErrorGeneric = "The operation failed — try again, and if it keeps failing retry with a different backup.",
    chooseBackupFile = "Choose backup file from device",
    importFileTooLarge = "File exceeds the backup size limit (16MB) — choose a smaller backup.",
    backupSaveFailed = "Couldn't save the file — check the location and try again.",
    sectionBackup = "Privacy & Encrypted Backup",
    localPrivacy = "What do we know about you?",
    localPrivacyValue = "0 data uploaded (100% offline)",
    exportBackup = "Export Encrypted Backup (.sila.backup)",
    restoreBackup = "Restore Backup",
    crashReports = "Anonymous Crash Reports",
    deleteAccount = "Reset Data & Start Fresh",
    deleteAccountValue = "Local only (No Cloud)",
    exportDialogTitle = "Export Encrypted Backup",
    exportDialogDesc = "Categories, kinship, tags, and preferences will be encrypted with AES-256-GCM. Enter a strong password:",
    passwordLabel = "Password",
    exportButton = "Export Encrypted",
    encrypting = "Encrypting...",
    restoreDialogTitle = "Restore Backup",
    restoreDialogDesc = "Enter the password to preview and decrypt the backup:",
    previewButton = "Preview Backup",
    verifying = "Verifying...",
    previewDialogTitle = "Backup Preview",
    restoreModeMerge = "Merge with current data",
    restoreModeMergeDesc = "Update categories, kinship, and tags without deleting existing ones",
    restoreModeReplace = "Replace current data",
    restoreModeReplaceDesc = "Reset previous categories and tags, applying the backup payload",
    restorePrefsCheckbox = "Also restore app preferences and settings",
    startRestore = "Start Restore",
    aboutSection = "About Sila",
    versionTitle = "Version",
    versionValue = "1.0.0 (Phase 1 Local MVP)",
    privacyTerms = "Privacy Policy & Terms",
    rateApp = "Rate App & Feedback",
    linkManualButton = "Link manually",
    linkSearchHint = "Search by name or number (min 2 chars)",
    linkNoCandidates = "No matching contacts",
    linkFailedLabel = "Link failed — target contact not found",
    linkAllDone = "All unmatched entries linked ✓",
    resultDone = "Done",
    restoreResultTitle = "Restore Results"
)

/** ترجمة إجراء السحب إلى النص المعروض في عنصر الإعدادات العام. */
internal fun formatSwipeAction(action: ContactSwipeAction, strings: SettingsStrings): String = when (action) {
    ContactSwipeAction.CALL -> strings.swipeCall
    ContactSwipeAction.MESSAGE -> strings.swipeMessage
    ContactSwipeAction.NONE -> strings.swipeNone
}

/** إسناد نوع خطأ النسخ إلى النص المترجم المناسب (لا رسائل استثناء خام للمستخدم). */
internal fun SettingsStrings.backupError(kind: BackupErrorKind): String = when (kind) {
    BackupErrorKind.BLANK_PASSWORD -> backupErrorBlankPassword
    BackupErrorKind.DECRYPTION_FAILED -> backupErrorDecryptionFailed
    BackupErrorKind.NOT_CONFIGURED -> backupErrorNotConfigured
    BackupErrorKind.GENERIC -> backupErrorGeneric
}

internal fun formatCategory(category: Category, isArabic: Boolean): String = when (category) {
    Category.FAMILY -> if (isArabic) "العائلة 👨‍👩‍👧‍👦" else "Family"
    Category.FRIENDS -> if (isArabic) "الأصدقاء 🤝" else "Friends"
    Category.WORK -> if (isArabic) "العمل 💼" else "Work"
    Category.SERVICES -> if (isArabic) "الخدمات 🛠️" else "Services"
    Category.EMERGENCY -> if (isArabic) "الطوارئ 🚨" else "Emergency"
    Category.UNCATEGORIZED -> if (isArabic) "غير مصنف" else "Uncategorized"
}

/** تسميات علاقات الأرحام مترجمة (S1-15 — تذكرة #62) — مرآة مفاتيح arham_relation_* على iOS. */
private val relationLabelsArabic: Map<Relation, String> = mapOf(
    Relation.GRANDFATHER to "جد",
    Relation.GRANDMOTHER to "جدة",
    Relation.FATHER to "والد",
    Relation.MOTHER to "والدة",
    Relation.BROTHER to "أخ",
    Relation.SISTER to "أخت",
    Relation.SON to "ابن",
    Relation.DAUGHTER to "ابنة",
    Relation.SPOUSE to "زوج/زوجة",
    Relation.UNCLE_PATERNAL to "عم",
    Relation.AUNT_PATERNAL to "عمة",
    Relation.COUSIN_PATERNAL to "ابن عم",
    Relation.UNCLE_MATERNAL to "خال",
    Relation.AUNT_MATERNAL to "خالة",
    Relation.COUSIN_MATERNAL to "ابن خالة",
    Relation.IN_LAW to "صهر/حماة",
    Relation.CLOSE_FRIEND to "صديق قريب",
    Relation.CUSTOM to "علاقة أخرى"
)

private val relationLabelsEnglish: Map<Relation, String> = mapOf(
    Relation.GRANDFATHER to "Grandfather",
    Relation.GRANDMOTHER to "Grandmother",
    Relation.FATHER to "Father",
    Relation.MOTHER to "Mother",
    Relation.BROTHER to "Brother",
    Relation.SISTER to "Sister",
    Relation.SON to "Son",
    Relation.DAUGHTER to "Daughter",
    Relation.SPOUSE to "Spouse",
    Relation.UNCLE_PATERNAL to "Uncle (Paternal)",
    Relation.AUNT_PATERNAL to "Aunt (Paternal)",
    Relation.COUSIN_PATERNAL to "Cousin (Paternal)",
    Relation.UNCLE_MATERNAL to "Uncle (Maternal)",
    Relation.AUNT_MATERNAL to "Aunt (Maternal)",
    Relation.COUSIN_MATERNAL to "Cousin (Maternal)",
    Relation.IN_LAW to "In-law",
    Relation.CLOSE_FRIEND to "Close Friend",
    Relation.CUSTOM to "Other relation"
)

private fun formatRelation(relation: Relation, isArabic: Boolean): String =
    (if (isArabic) relationLabelsArabic else relationLabelsEnglish)[relation] ?: "؟"

/** قيم السجل غير المطابق (التصنيف وصلة الرحم) بتسميات مترجمة مفصولة عمودياً. */
internal fun formatUnmatchedLabel(entry: UnmatchedBackupEntry, isArabic: Boolean): String =
    buildList {
        entry.category?.let { add(formatCategory(it, isArabic)) }
        entry.arhamRelation?.let { add(formatRelation(it, isArabic)) }
    }.joinToString(" · ")
