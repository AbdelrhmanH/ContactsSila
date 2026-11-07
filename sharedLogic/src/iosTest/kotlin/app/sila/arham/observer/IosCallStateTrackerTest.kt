package app.sila.arham.observer

import app.sila.feature.arham.domain.ObservedCallEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * اختبارات المتتبع النقي لدورة مكالمة `CXCall` (T8-12 · خطة سطر 550) — مصفوفة القواعد:
 * نهايةُ الصادر المتصل حصراً، الوارد مستبعد بنيوياً، الأول من الاتصال يثبت اللحظة،
 * والانتهاء دائماً ينسى المعرّف (لا تراكم ولا اختلاق مدد صفرية/سالبة).
 */
class IosCallStateTrackerTest {

    private val tracker = IosCallStateTracker()

    // ===== الدورة الكاملة للصادر المتصل =====

    @Test
    fun outgoingConnectedCycle_emitsEndWithKnownMoments_only() {
        assertNull(
            tracker.onCallReported("c1", outgoing = true, hasConnected = false, hasEnded = false, nowMillis = 1_000L),
            "بدئية دايلة: لا نهاية",
        )
        assertNull(
            tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = false, nowMillis = 2_000L),
            "بدئية اتصال: لا نهاية",
        )

        val end = assertNotNull(
            tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 62_000L),
            "نهاية الصادر المتصل تُخرج نهايةً مرصودة",
        )
        assertEquals(2_000L, end.offhookAtMillis, "لحظة أول اتصال هي لحظة الرفع")
        assertEquals(62_000L, end.idleAtMillis)
        assertNull(end.ringingAtMillis, "لا إشارة رنين في CXCallObserver — تبقى مجهولةً بأمانة")
    }

    @Test
    fun repeatedConnectedCallbacks_firstConnectionMomentWins() {
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = false, nowMillis = 2_000L))
        // بلاغ اتصال متأخر متكرر (تلاحق البلاغات) — لا يزيح لحظة الرفع:
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = false, nowMillis = 9_000L))

        val end = assertNotNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 62_000L))
        assertEquals(2_000L, end.offhookAtMillis, "الأول من الاتصال يكفي — التكرار يُهمل")
    }

    // ===== الاستبعاد البنيوي: الوارد وغير المتصل =====

    @Test
    fun incomingCall_neverEmitsEnd_evenWhenConnected() {
        assertNull(tracker.onCallReported("c1", outgoing = false, hasConnected = false, hasEnded = false, nowMillis = 1_000L))
        assertNull(tracker.onCallReported("c1", outgoing = false, hasConnected = true, hasEnded = false, nowMillis = 2_000L))
        assertNull(
            tracker.onCallReported("c1", outgoing = false, hasConnected = true, hasEnded = true, nowMillis = 62_000L),
            "نهاية الوارد تُستبعد بنيوياً — ربطُها بمحاولة صادرةٍ مفتوحة اختلاقٌ محتمل",
        )
    }

    @Test
    fun outgoingNeverConnected_ended_emitsNothing() {
        // الصادر المرفوض/بلا جواب: لا رفع سماعة — لا نهاية ولا مدة مُختلقة:
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = false, hasEnded = false, nowMillis = 1_000L))
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = false, hasEnded = true, nowMillis = 5_000L))
    }

    // ===== قواعد النهاية: النسيان والانتقال الموجب =====

    @Test
    fun endedCallId_isForgotten_duplicateEndReport_emitsNothing() {
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = false, nowMillis = 2_000L))
        assertNotNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 62_000L))

        // مكالمة منتهية تُنسى مهما كانت دورتها — بلاغ نهاية مكرر لا يُنتج نهاية ثانية:
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 70_000L))
    }

    @Test
    fun endedReportForUnknownId_emitsNothing() {
        assertNull(
            tracker.onCallReported("ghost", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 1_000L),
            "بلا دورة مفتوحة — لا اختلاق نهاية من معرّف مجهول",
        )
    }

    @Test
    fun connectedAndEndedInSingleReport_zeroElapsed_emitsNothing() {
        // بلاغ واحد متأخر (اتصل وانتهى بين تسليمتين): لحظة الرفع = لحظة النهاية نفسها —
        // الانتقال غير موجب ⇒ لا مدة صفرية مُختلقة (مرآة قاعدة «المدة عند إشارة معلومة»):
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = false, hasEnded = false, nowMillis = 1_000L))
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 62_000L))
    }

    // ===== النظافة =====

    @Test
    fun clear_forgetsOpenCycles_noFabricationAfterStop() {
        assertNull(tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = false, nowMillis = 2_000L))
        tracker.clear()

        assertNull(
            tracker.onCallReported("c1", outgoing = true, hasConnected = true, hasEnded = true, nowMillis = 62_000L),
            "بعد التصفية لا دورة مفتوحة — بلاغ النهاية يُهمل بلا اختلاق",
        )
    }
}
