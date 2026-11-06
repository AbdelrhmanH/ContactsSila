package app.sila.feature.arham.domain

/**
 * إشارة فرز مُستخرجة من المنصة كبدائيات حصراً (T8-09 · AC-5) — لا أنواع Telecom في
 * عقود النطاق: القارئ في androidMain يحوّل `Call.Details` إلى هذا الحمل قبل النطاق.
 *
 * @param remoteNumber الرقم بعد تجريد مخطط `tel:`؛ `null` = محجوب/غائب — لا يُستنتج.
 * @param verificationPassed هل تحقق المنصة من الرقم (`VERIFICATION_STATUS_VERIFIED`).
 * @param timestampMillis لحظة رصد الإشارة.
 */
data class ScreenedCallSignal(
    val remoteNumber: String?,
    val verificationPassed: Boolean,
    val timestampMillis: Long,
)

/**
 * حالة استخدام: إدخال إشارة فرز وارد عبر آلة الحالات (T8-09 · AC-4/AC-5 · ADR-0006 §5).
 *
 * التصنيف `classifyScreeningSignal` يُخرج حدثاً `UNASSOCIATED`/`PENDING` بنتيجة `UNKNOWN`
 * — **بلا حل رقم→جهة** (ADR-0007 يمنع اعتماد أرحام على جهات الاتصال)، فيبقى الربط
 * مسؤولية مسار التأكيد اليدوي في T8-06. ثم الإدخال عبر `IngestCallEventUseCase`:
 * مطابقة منع التكرار بالرقم والاتجاه (`IN`) والنافذة، ودمجٌ يحفظ المصدر الأول،
 * وقاعدة AC-8 تحمي `lastContactedAt` — نتيجة `UNKNOWN` لا تؤهل أبداً.
 *
 * رقمٌ محجوب (بلا أساس منع تكرار) يُخزَّن حداً مستقلاً بمعرف حتمي من اللحظة —
 * لا يُختلق مفتاح `dedupeKey` (قرار D-2).
 */
class ScreeningSignalIngestionUseCase(
    private val ingest: IngestCallEventUseCase,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) {

    /**
     * @param signal إشارة الفرز المُستخرجة كبدائيات.
     * @param dedupeWindowMillis نافذة منع التكرار؛ الافتراضي 15 دقيقة (PROPOSED).
     */
    suspend operator fun invoke(
        signal: ScreenedCallSignal,
        dedupeWindowMillis: Long = CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS,
    ): CallEventIngestion {
        val candidate = stateMachine.classifyScreeningSignal(
            eventId = "screening-${signal.timestampMillis}",
            remoteNumber = signal.remoteNumber,
            timestampMillis = signal.timestampMillis,
            verificationPassed = signal.verificationPassed,
        )
        return ingest(candidate, dedupeWindowMillis)
    }
}
