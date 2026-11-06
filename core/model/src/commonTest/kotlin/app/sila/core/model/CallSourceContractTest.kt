package app.sila.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبار عقد `CallSource` الموسّع (خطة T8 §G.3): القيم الجديدة للمصادر المسماة،
 * وبقاء القيمتين الإرثيتين صالحتين للدوران عبر `valueOf` حتى تقرأها محولات الهجرة
 * من بيانات v2 النصية دون تحويل.
 */
class CallSourceContractTest {

    @Test
    fun callSource_hasTheFiveSourcesAndThreeObserversOfAdr0006() {
        val expected = listOf(
            "CALL_LOG", // إرثي
            "IN_APP",
            "OBSERVER", // إرثي
            "MANUAL",
            "ANDROID_CALL_SCREENING",
            "ANDROID_TELEPHONY_OBSERVER",
            "IOS_CALL_OBSERVER",
        )
        assertEquals(expected, CallSource.entries.map { it.name })
    }

    @Test
    fun callSource_legacyTextValuesRoundTripThroughValueOf() {
        // ضمان الهجرة §G.3: قيم v2 النصية تدور كما هي — CALL_LOG/OBSERVER لا تُحوَّلان.
        assertEquals(CallSource.CALL_LOG, CallSource.valueOf("CALL_LOG"))
        assertEquals(CallSource.OBSERVER, CallSource.valueOf("OBSERVER"))
    }
}
