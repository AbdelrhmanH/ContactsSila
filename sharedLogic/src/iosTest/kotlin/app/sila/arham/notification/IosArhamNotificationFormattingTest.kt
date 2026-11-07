package app.sila.arham.notification

import app.sila.feature.arham.domain.OccasionAlertKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبارات المُنبِّئات النقية لنصوص إشعارات أرحام (T8-12 · §M — اصطلاح مفاتيح iOS
 * اليدوي `_one`/`_many` بلا `.stringsdict`): اختيار الفرد/الجمع ومصفوفة المناسبات
 * الأربعة، والتنسيق `%@` للاسم و`%lld` للعدد — النصوص نفسها مواردُ حزمةٍ تُختبر على
 * المحاكي عبر مسار الحزمة، وهنا تُثبَّت القرارات المحضة.
 */
class IosArhamNotificationFormattingTest {

    // ===== جسد الرسالة المجمعة: فرد/جمع/صفر =====

    @Test
    fun digestBody_zeroAndOneAdditional_useOneFormatWithoutCount() {
        // مرآة أندرويد T808-R4: 0 و1 كلاهما صيغة الفرد («دعوة متأخرة» بلا عدّاد):
        assertEquals(
            "لدى أم سارة دعوة متأخرة",
            formatDigestBody(
                additionalOverdueCount = 0,
                oneFormat = "لدى %@ دعوة متأخرة",
                manyFormat = "لدى %@ متأخرون +%lld",
                displayName = "أم سارة",
            ),
        )
        assertEquals(
            "لدى أم سارة دعوة متأخرة",
            formatDigestBody(
                additionalOverdueCount = 1,
                oneFormat = "لدى %@ دعوة متأخرة",
                manyFormat = "لدى %@ متأخرون +%lld",
                displayName = "أم سارة",
            ),
            "الواحد لا يُختلق له عدّاد +1 — نفس نصّ الفرد (تعيين أندرويد القائم)",
        )
    }

    @Test
    fun digestBody_twoAndMore_useManyFormatWithFullCount() {
        // اثنان فأكثر: صيغة الجمع والعدّاد إجمالي الإضافات لا -1 (نفس عقد أندرويد):
        assertEquals(
            "لدى أم سارة متأخرون +2",
            formatDigestBody(
                additionalOverdueCount = 2,
                oneFormat = "لدى %@ دعوة متأخرة",
                manyFormat = "لدى %@ متأخرون +%lld",
                displayName = "أم سارة",
            ),
        )
        assertEquals(
            "لدى أم سارة متأخرون +5",
            formatDigestBody(
                additionalOverdueCount = 5,
                oneFormat = "لدى %@ دعوة متأخرة",
                manyFormat = "لدى %@ متأخرون +%lld",
                displayName = "أم سارة",
            ),
        )
    }

    // ===== جسد تنبيه المناسبة: مصفوفة 2×2 =====

    @Test
    fun occasionBody_fourKeyMatrix_picksKindAndTiming() {
        val formats = listOf("عيد ميلاد %@ اليوم", "عيد ميلاد %@ غداً", "ذكرى %@ اليوم", "ذكرى %@ غداً")
        val (birthdayToday, birthdayTomorrow, anniversaryToday, anniversaryTomorrow) = formats

        assertEquals(
            "عيد ميلاد ليلى اليوم",
            formatOccasionBody(
                kind = OccasionAlertKind.ON_DAY,
                type = "BIRTHDAY",
                displayName = "ليلى",
                birthdayToday = birthdayToday,
                birthdayTomorrow = birthdayTomorrow,
                anniversaryToday = anniversaryToday,
                anniversaryTomorrow = anniversaryTomorrow,
            ),
        )
        assertEquals(
            "عيد ميلاد ليلى غداً",
            formatOccasionBody(
                kind = OccasionAlertKind.DAY_BEFORE,
                type = "BIRTHDAY",
                displayName = "ليلى",
                birthdayToday = birthdayToday,
                birthdayTomorrow = birthdayTomorrow,
                anniversaryToday = anniversaryToday,
                anniversaryTomorrow = anniversaryTomorrow,
            ),
        )
        assertEquals(
            "ذكرى ليلى اليوم",
            formatOccasionBody(
                kind = OccasionAlertKind.ON_DAY,
                type = "ANNIVERSARY",
                displayName = "ليلى",
                birthdayToday = birthdayToday,
                birthdayTomorrow = birthdayTomorrow,
                anniversaryToday = anniversaryToday,
                anniversaryTomorrow = anniversaryTomorrow,
            ),
        )
        assertEquals(
            "ذكرى ليلى غداً",
            formatOccasionBody(
                kind = OccasionAlertKind.DAY_BEFORE,
                type = "ANNIVERSARY",
                displayName = "ليلى",
                birthdayToday = birthdayToday,
                birthdayTomorrow = birthdayTomorrow,
                anniversaryToday = anniversaryToday,
                anniversaryTomorrow = anniversaryTomorrow,
            ),
        )
    }
}
