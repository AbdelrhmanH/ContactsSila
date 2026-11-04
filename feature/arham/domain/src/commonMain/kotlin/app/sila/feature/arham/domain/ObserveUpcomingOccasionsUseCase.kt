package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * اشتقاق مناسبات أرحام القادمة وقتَ القراءة (D1 المصادَق — خطة §P-T8-05 و§M).
 *
 * المصدر: تقاطع جهات أرحام المرتبطة (`ArhamRepository.observeArhamContacts` — FAMILY
 * ورابط أرحام غير null) مع تواريخ BIRTHDAY/ANNIVERSARY على النموذج نفسه — **لا كتابة
 * ولا جدول ولا حالة مشتقة مُخزَّنة**؛ التكرار سنوي بلا اعتبار لسنة المصدر.
 *
 * النافذة: 7 أيام شاملة الطرفين (0 = اليوم) — بطاقة «أقرب مناسبة خلال 7 أيام وإلا
 * رسالة تحفيزية» (AC-2/§M؛ أول عنصر في القائمة هو الأقرب).
 *
 * الساعة والمنطقة الزمنية محقونة (`AppClock` + `TimeZone`) — النقاء قابل للاختبار
 * تحديداً، ومنطقة افتراضية للنظام فقط عند البناء عبر Koin.
 */
class ObserveUpcomingOccasionsUseCase(
    private val repository: ArhamRepository,
    private val clock: AppClock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    operator fun invoke(): Flow<List<ArhamOccasion>> =
        repository.observeArhamContacts().map { contacts ->
            val today = clock.now().toLocalDateTime(timeZone).date
            contacts
                .flatMap { contact ->
                    contact.dates.mapNotNull { date ->
                        if (date.type !in ALERTED_DATE_TYPES) return@mapNotNull null
                        val next = resolveNextAnnualOccurrence(date.month, date.day, today)
                            ?: return@mapNotNull null
                        ArhamOccasion(
                            contactId = contact.id,
                            displayName = contact.name.displayName,
                            type = date.type,
                            label = date.label,
                            month = date.month,
                            day = date.day,
                            year = date.year,
                            nextOccurrence = next,
                            daysUntil = daysUntil(today, next),
                        )
                    }
                }
                .filter { it.daysUntil in 0..WINDOW_DAYS }
                .sortedWith(
                    compareBy({ it.daysUntil }, { it.displayName }, { it.contactId }),
                )
        }.flowOn(dispatchers.default)

    companion object {
        /** نافذة بطاقة المناسبات: 7 أيام شاملة الطرفين. */
        const val WINDOW_DAYS = 7

        /**
         * أنواع التواريخ المؤهِّلة لتنبيه مناسبة. CUSTOM مستبعد عمداً — D1 المصادَق:
         * لا مناسبات مخصصة في المرحلة الأولى.
         */
        val ALERTED_DATE_TYPES: Set<String> = setOf("BIRTHDAY", "ANNIVERSARY")
    }
}
