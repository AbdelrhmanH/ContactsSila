package app.sila.feature.arham.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * دلالات ساعات الهدوء (§M — REQUIRED، T8-08): منطقٌ حتّي مشترك (Android/iOS) بلا ساعة
 * ولا منصة — المستدعي يمرّر لحظة الإطلاق المرشحة والمنطقة الزمنية، والدالة تُعيد إمّا
 * اللحظة نفسها أو لحظة نهاية نافذة الهدوء.
 *
 * القواعد المثبتة بالاختبارات (قرارات مشروحة في تقرير T8-08):
 * - **النافذة `[start, end)` بالساعة المحلية:** ساعة البداية داخل النافذة، وساعة النهاية
 *   هي لحظة الاستيقاظ (الإطلاق عندها مسموح).
 * - **الاتجاهان:** `start < end` (نافذة نهارية 13→15) و`start > end` (ليلية عابرة
 *   منتصف الليل 23→8 — الافتراضي المُثبَّت)؛ الإطلاق داخل النافذة يُؤجَّل إلى نهايتها.
 * - **`start == end`:** نافذة بطول صفري — لا هدوء (لا تُختلق نافذة غير موجودة).
 * - **ساعات غير صالحة (خارج 0..23):** ساعات الهدوء معطَّلة (الإعداد غير موجود دلالياً —
 *   كاتب التفضيلات يضيّق النطاق مسبقاً، وهذا تحصين عمق للبناء المباشر).
 * - فجوة/تراكب التوقيت الصيفي: `LocalDateTime.toInstant` يحسمها بقواعد المنطقة —
 *   والإطلاق غير دقيق أصلاً (D2: بلا وعد بوقت محدد).
 */
object QuietHours {

    /**
     * يُعيد لحظة الإطلاق المؤجَّلة إذا كانت داخل نافذة الهدوء، وإلا اللحظة نفسها.
     *
     * @param fireAtMillis لحظة الإطلاق المرشحة (epoch millis).
     * @param timeZone المنطقة الزمنية لحساب الساعة المحلية.
     * @param startHour ساعة بدء الهدوء (شاملة).
     * @param endHour ساعة انتهاء الهدوء (حظة الاستيقاظ — غير شاملة).
     */
    fun deferIfQuiet(
        fireAtMillis: Long,
        timeZone: TimeZone,
        startHour: Int,
        endHour: Int,
    ): Long {
        val validWindow = startHour in 0..23 && endHour in 0..23 && startHour != endHour
        if (!validWindow) return fireAtMillis

        val local: LocalDateTime = Instant.fromEpochMilliseconds(fireAtMillis).toLocalDateTime(timeZone)
        val minutesOfDay = local.hour * 60 + local.minute
        val startMinutes = startHour * 60
        val endMinutes = endHour * 60

        val deferred: LocalDateTime? = when {
            // نافذة نهارية (start < end): الهدوء من start:00 حتى end:00 في اليوم نفسه.
            startHour < endHour ->
                if (minutesOfDay in startMinutes until endMinutes) local.date.atTime(endHour, 0) else null

            // نافذة ليلية عابرة منتصف الليل (start > end): الجزء المسائي يؤجل إلى غدٍ
            // بنهايتها، والجزء الصباحي (قبل end) يؤجل إلى نهاية اليوم نفسه.
            minutesOfDay >= startMinutes -> local.date.plus(1, DateTimeUnit.DAY).atTime(endHour, 0)
            minutesOfDay < endMinutes -> local.date.atTime(endHour, 0)
            else -> null
        }

        return deferred?.toInstant(timeZone)?.toEpochMilliseconds() ?: fireAtMillis
    }
}
