package app.sila.feature.arham.domain

import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource

/**
 * نتيجة ربط نهاية مرصودة بمحاولة صِلَة مفتوحة (T8-09 · D-2) — للتشخيص والاختبار؛
 * لا تُعرض على المستخدم. القاعدة الجامعة: **المراقب لا يكتب صفاً جديداً قط** —
 * إما إثراء محاولة `IN_APP` مفتوحة وحيدة، أو لا شيء (fail-safe).
 */
sealed interface ObservedCallEndCorrelation {

    /** أُثريت المحاولة المفتوحة الوحيدة بدليل نهاية المراقب وخُزّنت. */
    data class Correlated(val event: CallEvent) : ObservedCallEndCorrelation

    /** لا محاولة مفتوحة مطابقة ضمن نافذة الحياة — لا كتابة. */
    data object NoOpenAttempt : ObservedCallEndCorrelation

    /** محاولتان مفتوحتان أو أكثر — الغموض لا يُحل بصمت (فلسفة ADR-0006 §5): لا كتابة. */
    data class Ambiguous(val candidates: List<CallEvent>) : ObservedCallEndCorrelation

    /**
     * رُصد رنينٌ بين بدء المحاولة والنهاية — الـ OFFHOOK كان لمكالمة واردة مجابة لا
     * لاتصالنا الصادر: إثبات `CONNECTED` هنا اختلاق. لا كتابة (fail-safe، قد يفوّت
     * إثراءً حقيقياً في حالة انتظار نداء نادرة — التحيّز المعلن ضد الدليل المُختلق).
     */
    data object RingingIntervened : ObservedCallEndCorrelation
}

/**
 * حالة استخدام: ربط نهاية مكالمة مرصودة بالحالة (مراقب Telephony — AC-17) بمحاولة
 * اتصال من صِلَة مفتوحة (T8-09 · D-2). هذا هو **حل فجوة الـ dedupeKey**: بدل اختراع
 * مفتاح دمج للمراقب (لا يملك رقماً ولا اتجاهاً — حالة فقط)، تُربط نهايةُ `OFFHOOK→IDLE`
 * بالمحاولة الوحيدة المفتوحة ضمن نافذة الحياة، فيُثرَّى الحدث القائم عبر
 * `onInAppCallEnded` (نتيجة `CONNECTED` بثقة `MEDIUM` — لا `COMPLETED` قط).
 *
 * قواعد fail-safe (كل انحراف عن الوضع الوحيد المتوقع ⇒ لا كتابة):
 * - صفر مرشحين (لا محاولة مفتوحة) أو أكثر من واحد ⇒ لا كتابة.
 * - رنينٌ رُصد بعد بدء المحاولة (قاعدة التمييز) ⇒ لا كتابة.
 * - `CONNECTED` لا يؤهل لقاعدة AC-8 ⇒ **لا يُستدعى `updateLastContacted` هنا إطلاقاً** —
 *   بوابة آخر تواصل تبقى آلة الحالات حصراً.
 *
 * الكتابة `upsert` مباشرة (لا عبر `IngestCallEventUseCase`): هذا إثراءُ صفٍّ معروف
 * بمعرّفه لا رصدٌ مرشّح جديد — وإعادة إدخال الحدث نفسه «استبدال رصد لا دمج» أصلاً
 * (ADR-0006 §5: الحدث ليس مكرراً لنفسه).
 */
class CorrelateObservedCallEndUseCase(
    private val repository: ArhamCallEventRepository,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
    /**
     * مصدرُ دليل المراقب في اتحاد الأدلة (ADR-0006 §5) — أندرويد الافتراضي التوافقي؛
     * iOS تمرّر `CallSource.IOS_CALL_OBSERVER` من وحدتها (T8-12) فلا يُنسب دليلُ
     * مراقبها إلى منصةٍ أخرى (العقد المصادَق: المصدر يمثّل المنصة لا يكذب عليها).
     */
    private val observerSource: CallSource = CallSource.ANDROID_TELEPHONY_OBSERVER,
) {

    /**
     * نافذة حياة المرشحين: أقدم محاولة `startedAt` مقبولة للربط بلحظة رفع السماعة —
     * فوقها تُعد المحاولة قديمةً بعيداً عن هذا الانتقال. قيمة انطلاق بمقدار نافذة
     * منع التكرار نفسها (مقياس «مكالمة جارية الآن»)، مستقلة عنها نطاقاً ودلالة.
     */
    companion object {
        const val DEFAULT_OBSERVATION_LIVENESS_MILLIS: Long =
            CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS
    }

    /**
     * @param end نهاية المرصود من حامل الانتقالات (بلا هوية).
     * @param livenessWindowMillis نافذة حياة المحاولة المفتوحة قبل رفع السماعة.
     */
    suspend operator fun invoke(
        end: ObservedCallEnd,
        livenessWindowMillis: Long = DEFAULT_OBSERVATION_LIVENESS_MILLIS,
    ): ObservedCallEndCorrelation {
        val windowStart = end.offhookAtMillis - livenessWindowMillis
        val openAttempts = repository
            .findEventsBetween(fromMillis = windowStart, toMillis = end.idleAtMillis)
            .filter {
                it.primarySource == CallSource.IN_APP &&
                    it.direction == CallDirection.OUT &&
                    it.outcome == CallOutcome.ATTEMPTED &&
                    it.endedAt == null
            }
        if (openAttempts.isEmpty()) return ObservedCallEndCorrelation.NoOpenAttempt
        if (openAttempts.size > 1) return ObservedCallEndCorrelation.Ambiguous(openAttempts)

        val attempt = openAttempts.single()
        val startedAt = attempt.startedAt
        val ringingAt = end.ringingAtMillis
        if (ringingAt != null && startedAt != null && ringingAt >= startedAt) {
            return ObservedCallEndCorrelation.RingingIntervened
        }

        val enriched = stateMachine.onInAppCallEnded(
            observed = attempt,
            offhookAtMillis = end.offhookAtMillis,
            idleAtMillis = end.idleAtMillis,
        )
        if (enriched === attempt) {
            // تحصين: الفلاتر أعلاه تضمن استيفاء شروط الإثراء — عودةُ الحدث كما هو تعني
            // انحرافاً عن العقود، والصمت أأمن من كتابة دليلٍ غير مُثرى.
            return ObservedCallEndCorrelation.NoOpenAttempt
        }
        // اتحاد الدليل: المراقب شاهد النهاية — دليله يُضاف ولا يستبدل المصدر الأول (ADR-0006 §5).
        // مصدر الدليل مُحقون (أندرويد افتراضياً؛ iOS تمرّر IOS_CALL_OBSERVER — T8-12) فلا
        // يُنسب دليلُ مراقب منصةٍ إلى منصة أخرى.
        val withObserverEvidence = enriched.copy(
            evidenceSources = enriched.evidenceSources + observerSource,
        )
        repository.upsertEvent(withObserverEvidence)
        return ObservedCallEndCorrelation.Correlated(withObserverEvidence)
    }
}
