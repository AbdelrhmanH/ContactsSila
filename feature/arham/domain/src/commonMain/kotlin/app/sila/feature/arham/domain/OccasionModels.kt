package app.sila.feature.arham.domain

import kotlinx.datetime.LocalDate

/**
 * مناسبة مشتقة **وقتَ القراءة** (D1 المصادَق — خطة §W/§I-D1): لا جدول `arham_occasions`
 * ولا مناسبات يملؤها المستخدم في المرحلة الأولى؛ المناسبة تُشتق حصراً من تقاطع جهات
 * أرحام المرتبطة (`ArhamRepository.observeArhamContacts`: FAMILY ورابط غير null)
 * مع تواريخ `ImportantDate` من نوع BIRTHDAY/ANNIVERSARY.
 *
 * النوع CUSTOM مستبعد من الاشتقاق عمداً — هو ذات باب «المناسبات المخصصة» الذي أجاب
 * عنه التذييل في §W: لا دعم للمرحلة الأولى.
 */
data class ArhamOccasion(
    val contactId: Long,
    val displayName: String,
    /** نوع التاريخ كما في `ImportantDate.type` — BIRTHDAY أو ANNIVERSARY فقط (CUSTOM مستبعد). */
    val type: String,
    val label: String?,
    val month: Int,
    val day: Int,
    /** سنة المصدر الأصلية (ميلاد/زواج) — للعرض لاحقاً؛ لا تدخل في حساب التكرار السنوي. */
    val year: Int?,
    /** أقرب تكرار سنوي مسامَح به من «اليوم» (شامل اليوم نفسه). */
    val nextOccurrence: LocalDate,
    /** أيام حتى المناسبة: 0 = اليوم، 1 = غداً … (ضمن نافذة `ObserveUpcomingOccasionsUseCase.WINDOW_DAYS`). */
    val daysUntil: Int,
)

/** نوع تنبيه المناسبة (§M): صباح يوم المناسبة، وقبلها بيوم اختيارياً — بلا حالة إخفاء مُخزَّنة في المرحلة الأولى (D1 بند 7). */
enum class OccasionAlertKind { ON_DAY, DAY_BEFORE }

/** تنبيه مناسبة محسوب جاهز للاستهلاك (بطاقة اللوحة / إشعار T8-08). */
data class OccasionAlert(
    val kind: OccasionAlertKind,
    val occasion: ArhamOccasion,
)
