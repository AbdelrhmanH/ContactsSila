package app.sila.feature.arham.presentation

import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation

/**
 * نصوص شاشات أرحام (T8-11 · AC-1/AC-2/AC-13 · Issue #29) — عقدٌ صافٍ تُنفِّذه sharedUI
 * عبر موارد الترجمة الحقيقية (`values/` العربية الافتراضية و`values-en/`) — **لا نص
 * ثابت في كود الواجهة إطلاقاً** (D.3-3/AGENTS.md). القوالب ذات العدد تستخدم `%1$d`/
 * `%2$s` وتُعوَّض يدوياً (لا `String.format` — غير متاح في commonMain) بنفس نمط
 * `ContactsStrings`. صناديق الجمع العربية تُفصَل يدوياً في المنفِّذ عبر مفاتيح متعددة
 * (نمط T808-R4).
 */
interface ArhamStrings {
    val screenTitle: String
    fun podiumTitle(isWeek: Boolean): String
    val statCalls: String
    val statPaused: String
    val statConnectedLabel: String
    fun statConnected(contacted: Int, total: Int): String
    val statGoalLabel: String
    fun statGoal(percent: Int): String
    val periodWeek: String
    val periodMonth: String
    val openTree: String
    val closeTree: String
    val emptyTitle: String
    val emptyBody: String
    val celebrationTitle: String
    val actionCancel: String
    val rowCallLabel: String

    /** حالة الصف/العقدة — العرض دلالي حصراً (حلقة اللون من النظام التصميمي). */
    fun statusText(status: ArhamStatus, daysSinceLastContact: Int?): String

    fun confirmationsBadge(count: Int): String
    fun confirmationEventLine(directionLabel: String, durationMin: Int?): String
    val confirmContacted: String
    val confirmNoAnswer: String
    val confirmNotRelated: String
    val confirmLater: String
    val directionOutgoing: String
    val directionIncoming: String
    val directionMissed: String
    val directionUnknown: String

    val occasionsTitle: String

    /** سطر الشخص في بطاقة المناسبة — سياق القرابة مع الاسم (O-8: «عمتك سعاد»). */
    fun occasionPerson(relationLabel: String, displayName: String): String

    /** سطر اليوم — مناسبة اليوم تُصاغ بجملة كاملة (صباح يوم المناسبة — §M). */
    fun occasionToday(personLine: String): String

    /** سطر الأيام المتبقية (1..7 — نافذة البطاقة). */
    fun occasionInDays(daysUntil: Int, personLine: String): String

    fun kinshipTitle(displayName: String): String
    val kinshipStepRelation: String
    val kinshipStepFrequency: String
    val kinshipParentLabel: String
    val kinshipParentNone: String
    val kinshipPauseLabel: String
    val kinshipPauseOneMonth: String
    val kinshipSave: String
    val kinshipFrequencyRequired: String
    fun frequencyLabel(frequency: ArhamFrequency): String
    fun relationLabel(relation: Relation): String
    fun layerLabel(layer: KinshipLayer): String

    val treeTitle: String
    val treeCenterOnMe: String
    val treeEmpty: String

    /** زر «+» في نهاية كل صف توليدة (AC-13: عقدة فارغة) — رمز من المورد (لا حرف مضمن). */
    val treeAdd: String
    val treePickCandidate: String
    val treeNodeCall: String
    val treeNodeEditKinship: String

    val emergencyBannerTitle: String
    val emergencyStop: String
    fun emergencyAttempts(attempt: Int, max: Int): String

    val screeningConsentTitle: String
    val screeningConsentBody: String
    val screeningConsentAction: String
    val screeningConsentDismiss: String
}
