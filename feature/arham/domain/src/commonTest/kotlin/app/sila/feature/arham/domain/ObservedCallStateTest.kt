package app.sila.feature.arham.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * اختبارات حامل انتقالات حالة الهاتف (T8-09 · AC-17): دورةٌ نظيفة لكل نهاية —
 * أول رنين/رفع يكفي، عودةُ الخمول بلا رفع لا تُنتج شيئاً ولا تُسرب رنيناً قديماً،
 * والمدد غير الموجبة تُهمل (fail-safe — بلا مدد مُختلقة).
 */
class ObservedCallStateTest {

    @Test
    fun `dial cycle without ringing produces end with null ringing`() {
        val state = ObservedCallState()
        state.onOffhook(1_000L)
        val end = state.onIdle(61_000L)
        assertEquals(ObservedCallEnd(offhookAtMillis = 1_000L, idleAtMillis = 61_000L, ringingAtMillis = null), end)
    }

    @Test
    fun `incoming answered call carries its ringing moment`() {
        val state = ObservedCallState()
        state.onRinging(500L)
        state.onOffhook(1_000L)
        val end = state.onIdle(61_000L)
        assertEquals(ObservedCallEnd(offhookAtMillis = 1_000L, idleAtMillis = 61_000L, ringingAtMillis = 500L), end)
    }

    @Test
    fun `first ringing and first offhook win over repeats`() {
        val state = ObservedCallState()
        state.onRinging(500L)
        state.onRinging(600L) // رنين متكرر — يُهمل
        state.onOffhook(1_000L)
        state.onOffhook(1_100L) // رفع متكرر — يُهمل
        val end = state.onIdle(61_000L)
        assertEquals(500L, end?.ringingAtMillis)
        assertEquals(1_000L, end?.offhookAtMillis)
    }

    @Test
    fun `idle without offhook produces nothing and resets the ringing`() {
        val state = ObservedCallState()
        state.onRinging(500L)
        assertNull(state.onIdle(1_000L), "مكالمة واردة لم تُجب — لا نهاية")

        // التصفير إلزامي: رنينٌ قديم لا يتسرب إلى الدورة التالية (اتصالنا الصادر يبقى بلا رنين)
        state.onOffhook(2_000L)
        val nextEnd = state.onIdle(3_000L)
        assertEquals(null, nextEnd?.ringingAtMillis)
    }

    @Test
    fun `non-positive transition window is rejected and holder resets`() {
        val state = ObservedCallState()
        state.onOffhook(1_000L)
        assertNull(state.onIdle(1_000L), "نفس اللحظة — مدة صفرية غير دليل")
        assertNull(state.onIdle(999L), "ترتيب مقلوب — غير دليل")
        // وبعد الرفض يتوفر الحامل لنفس دورة الرفع (رفض الخمول يصفّر أيضاً — الدورة التالية نظيفة)
        state.onOffhook(2_000L)
        assertEquals(2_000L, state.onIdle(62_000L)?.offhookAtMillis)
    }

    @Test
    fun `holder is clean after each produced end`() {
        val state = ObservedCallState()
        state.onOffhook(1_000L)
        state.onIdle(2_000L)
        assertNull(state.onIdle(3_000L), "خمول ثانٍ بلا رفع جديد — لا نهاية مزدوجة")
        state.onOffhook(4_000L)
        assertEquals(4_000L, state.onIdle(5_000L)?.offhookAtMillis, "الدورة التالية تُنتج بشكل طبيعي")
    }
}
