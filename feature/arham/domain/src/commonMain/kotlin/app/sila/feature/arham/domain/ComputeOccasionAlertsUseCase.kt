package app.sila.feature.arham.domain

/**
 * حساب تنبيهات المناسبات من المشتقَّات الجاهزة (§M — T8-05): تنبيه صباح يوم المناسبة
 * نفسها، وقبلها بيوم **اختيارياً**. لا تفضيل مُخزَّن لتبديل «اليوم السابق» بعد — T8-08
 * يمرِّر الراية من التفضيلات حين تُضاف؛ ولا حالة إخفاء/إسناد تُخزَّن في المرحلة الأولى
 * (D1 بند 7: «تذكيري غداً» إزاحةُ جدولةٍ لا كتابةُ حالة).
 *
 * نقية بلا ساعة: `daysUntil` جاهز في [ArhamOccasion] من حالة الاشتقاق المحقِنة.
 */
class ComputeOccasionAlertsUseCase {
    operator fun invoke(
        occasions: List<ArhamOccasion>,
        includeDayBefore: Boolean = false,
    ): List<OccasionAlert> = occasions.flatMap { occasion ->
        when {
            occasion.daysUntil == 0 -> listOf(OccasionAlert(OccasionAlertKind.ON_DAY, occasion))
            includeDayBefore && occasion.daysUntil == 1 ->
                listOf(OccasionAlert(OccasionAlertKind.DAY_BEFORE, occasion))
            else -> emptyList()
        }
    }
}
