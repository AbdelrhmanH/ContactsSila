package app.sila.feature.contacts.presentation.onboarding

import app.sila.core.model.Relation

/**
 * عقد نصوص شاشات التهيئة الأولى لطبقة الواجهة المشتركة (Compose).
 *
 * التنفيذ مصدره موارد الترجمة الحقيقية (sharedUI composeResources — `values/` العربية
 * الافتراضية و `values-en/`) ولا يوجد أي نص ثابت في كود الواجهة (AGENTS.md).
 * القالبان الافتراضيان [topFiveCounter] و [relationLabel] يُكوَّنان من قيم الأعضاء
 * المحالة من الموارد، فتبقى سلاسل البيانات مكتفية بموارد الترجمة وحدها.
 */
interface OnboardingStrings {
    // ترحيب (3 شرائح: نظّم · خدمات حية · أرحام)
    val welcomeTitleOrganize: String
    val welcomeBodyOrganize: String
    val welcomeTitleLive: String
    val welcomeBodyLive: String
    val welcomeTitleArham: String
    val welcomeBodyArham: String
    val actionStart: String
    val actionContinue: String

    // شرح صلاحية جهات الاتصال (مصفوفة الأذونات — Issue #20)
    val permissionTitle: String
    val permissionBody: String
    val permissionGrantButton: String
    val permissionSkipButton: String
    val permissionDeniedOnceBody: String
    val permissionDeniedForeverBody: String
    val permissionOpenSettingsButton: String
    val permissionLimitedBody: String

    // الضيف (بدون OTP في المرحلة 1)
    val guestTitle: String
    val guestBody: String
    val guestLoginNote: String
    val guestSkipButton: String

    // أهم 5 أشخاص
    val topFiveTitle: String
    val topFiveBody: String
    val topFiveSearchHint: String
    val topFiveLoadingLabel: String
    val topFiveEmptyTitle: String
    val topFiveEmptyBody: String
    val topFivePermissionMissingBody: String
    val topFiveLimitedBody: String
    val topFiveNoResultsLabel: String
    val topFiveErrorBody: String
    val errorTitle: String
    val errorRetryButton: String
    val topFiveSaveButton: String
    val topFiveSkipButton: String

    /** قالب العدّاد مثل: «تم اختيار %1$d من 5» (موقع %1$d عدد المختارين). */
    val topFiveCounterTemplate: String

    /** صيغة عدّاد المختارين باستبدال العلامة %1$d — محلية اللاحقة عبر الموارد. */
    fun topFiveCounter(selected: Int): String =
        topFiveCounterTemplate.replace(COUNTER_PLACEHOLDER, selected.toString())

    // صلة القرابة السريعة
    val relationFather: String
    val relationMother: String
    val relationBrother: String
    val relationSister: String
    val relationSpouse: String
    val relationSon: String
    val relationDaughter: String
    val relationInLaw: String
    val relationCloseFriend: String
    val relationCustom: String

    /** تسمية الصلة القرابة السريعة (تُقسَّم إلى عائلة سريعة للمخطط المبسّط). */
    fun relationLabel(relation: Relation): String = when (relation) {
        Relation.FATHER -> relationFather
        Relation.MOTHER -> relationMother
        Relation.BROTHER -> relationBrother
        Relation.SISTER -> relationSister
        Relation.SPOUSE -> relationSpouse
        Relation.SON -> relationSon
        Relation.DAUGHTER -> relationDaughter
        Relation.IN_LAW -> relationInLaw
        Relation.CLOSE_FRIEND -> relationCloseFriend
        Relation.CUSTOM -> relationCustom
        else -> relationCustom
    }

    // جارٍ التنظيم
    val organizingTitle: String
    val organizingBody: String

    private companion object {
        /** علامة رقم العدّاد (نسبة KMP — string formatting عبر الموارد نفسها). */
        const val COUNTER_PLACEHOLDER = "%1\$d"
    }
}
