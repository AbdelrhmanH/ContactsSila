package app.sila.feature.arham.domain

import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import kotlin.math.abs

/**
 * نتيجة البحث عن تطابق منع التكرار لحدث مرشّح (ADR-0006 §5).
 */
sealed interface DedupeMatch {

    /** لا يوجد حدث قائم مطابق — المرشّح يُخزّن حداً مستقلاً. */
    data object None : DedupeMatch

    /** تطابق وحيد — يُدمج المرشّح فيه دون سؤال المستخدم. */
    data class Unique(val existing: CallEvent) : DedupeMatch

    /** تطابقات متعددة (مطابق غامض) — الاختيار للمستخدم وفق ADR-0006 §5. */
    data class Ambiguous(val matches: List<CallEvent>) : DedupeMatch
}

/**
 * إجابة المستخدم على سؤال التأكيد الاحتياطي «تم التواصل؟» (ADR-0006 §5):
 * تم / لم يرد / غير متعلقة / لاحقاً.
 */
enum class ConfirmationAnswer {
    /** «تم» — تأكيد تفاعل مكتمل. */
    CONTACTED,

    /** «لم يرد» — تأكيد عدم الرد. */
    NO_ANSWER,

    /** «غير متعلقة» — رفض الحدث كتفاعل أرحام. */
    NOT_RELATED,

    /** «لاحقاً» — تأجيل القرار؛ يبقى الحدث `PENDING`. */
    LATER
}

/**
 * آلة حالات أحداث المكالمات — منطق نقي بلا منصة ولا Room (ADR-0006 §دورة حياة و§5 و§6).
 *
 * مسؤولياتها الحصرية:
 * 1. تصنيف إشارات `IN_APP` (نية الاتصال ← `ATTEMPTED`؛ نهاية `OFFHOOK→IDLE` ← `CONNECTED`
 *    بمدة تقريبية — **مطلقاً لا `COMPLETED`**: المدة التقريبية لا تثبت أن المكالمة رُدّ عليها).
 * 2. منع التكرار: مطابقة بالرقم + الاتجاه (عند معرفتيهما) + نافذة زمنية، أو بمفتاح `dedupeKey`،
 *    ودمج يحفظ المصدر الأول وكل مصادر الدليل (ADR-0006 §5).
 * 3. حسم إجابات التأكيد الأربع (ADR-0006 §5).
 * 4. قاعدة تحديث أرحام `updatesLastContacted` (ADR-0006 §6 / AC-8) — بوابة `lastContactedAt` الوحيدة.
 */
class CallEventStateMachine {

    companion object {

        /**
         * نافذة منع التكرار الافتراضية (15 دقيقة) — **PROPOSED**: قيمة انطلاق قابلة للضبط
         * عند المعايرة، غير مثبتة في ADR-0006.
         */
        const val DEFAULT_DEDUPE_WINDOW_MILLIS: Long = 15L * 60L * 1000L
    }

    /**
     * قاعدة تحديث أرحام (ADR-0006 §6 / AC-8): يُحدَّث `lastContactedAt` فقط عندما يكون الحدث
     * مرتبطاً بجهة اتصال **و** لم يكن مرفوضاً **و** إما:
     * - تأكيد المستخدم الصريح لتفاعل مكتمل (`CONFIRMED` + `COMPLETED`)، أو
     * - اكتمال مثبت بالمنصة: `COMPLETED` مع اتصال ونهاية ومدة > 0.
     *
     * `REJECTED` لا يُحدّث أبداً حتى مع أدلة منصة كاملة — رفضُ المستخدم يعلو على كل دليل
     * (خطة T8 §D.3-4: «REJECTED must never update it»).
     */
    fun updatesLastContacted(event: CallEvent): Boolean {
        if (event.contactId == null) return false
        if (event.confirmation == CallConfirmation.REJECTED) return false
        val confirmedCompletion =
            event.confirmation == CallConfirmation.CONFIRMED && event.outcome == CallOutcome.COMPLETED
        val evidencedCompletion = event.outcome == CallOutcome.COMPLETED &&
            event.connectedAt != null &&
            event.endedAt != null &&
            (event.durationSec ?: 0) > 0
        return confirmedCompletion || evidencedCompletion
    }

    /**
     * هل يطابق `candidate` حداً قائماً لمنع التكرار؟
     *
     * قواعد المطابقة (ADR-0006 §5):
     * - الحدث نفسه (`id` متساوٍ) ليس «مكرراً» لنفسه — إعادة إدخاله استبدالٌ لرصده لا دمج.
     * - مفتاح `dedupeKey` متساوٍ (عند وجوده للطرفين): دليل صريح كافٍ وحده.
     * - وإلا: رقم متطابق غير فارغ + اتجاهان متطابقان إذا كُلاهما معروف + فرق زمني ≤ النافذة.
     */
    fun matchesDedupe(
        candidate: CallEvent,
        other: CallEvent,
        windowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
    ): Boolean {
        if (candidate.id == other.id) return false
        if (candidate.dedupeKey != null && candidate.dedupeKey == other.dedupeKey) return true
        val numberMatch = candidate.remoteNumber != null && candidate.remoteNumber == other.remoteNumber
        if (!numberMatch) return false
        val directionMatch =
            candidate.direction == null || other.direction == null || candidate.direction == other.direction
        if (!directionMatch) return false
        return abs(candidate.timestamp - other.timestamp) <= windowMillis
    }

    /**
     * يبحث عن تطابق منع التكرار بين قائمة الأحداث القائمة؛ تطابق وحيد فريد،
     * وتماسُّ تطابقات غموضٌ يُحل باختيار المستخدم (ADR-0006 §5).
     */
    fun findDedupeMatch(
        candidate: CallEvent,
        candidates: List<CallEvent>,
        windowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
    ): DedupeMatch {
        val matches = candidates.filter { matchesDedupe(candidate, it, windowMillis) }
        return when (matches.size) {
            0 -> DedupeMatch.None
            1 -> DedupeMatch.Unique(matches.first())
            else -> DedupeMatch.Ambiguous(matches)
        }
    }

    /**
     * دمج حدث مرشّح في حدث قائم (ADR-0006 §5): يُحفظ المصدر الأول ولا يُمحى الأصل —
     * `id` و`primarySource` من القائم، `evidenceSources` اتحادٌ، و`timestamp` الأقدم،
     * والفجوات الفارغة تُملأ من المرشّح، وقيم القائم غير الفارغة لا تُستبدل.
     *
     * حالات `confirmation`/`confidence`/`outcome`/`association` تبقى من الحدث القائم؛
     * أي ترقية (كرفع تأكيد يدوي إلى `CONFIRMED`) قرارٌ صريح عند مستدعٍ مخوّل — لا يحدث هنا صمتاً.
     */
    fun merge(existing: CallEvent, incoming: CallEvent): CallEvent = existing.copy(
        evidenceSources = existing.evidenceSources + incoming.evidenceSources,
        timestamp = minOf(existing.timestamp, incoming.timestamp),
        remoteNumber = existing.remoteNumber ?: incoming.remoteNumber,
        contactId = existing.contactId ?: incoming.contactId,
        direction = existing.direction ?: incoming.direction,
        startedAt = existing.startedAt ?: incoming.startedAt,
        connectedAt = existing.connectedAt ?: incoming.connectedAt,
        endedAt = existing.endedAt ?: incoming.endedAt,
        durationSec = existing.durationSec ?: incoming.durationSec,
        dedupeKey = existing.dedupeKey ?: incoming.dedupeKey,
        simSlot = existing.simSlot ?: incoming.simSlot,
    )

    /**
     * تصنيف نية اتصال من داخل صِلَة (`ACTION_CALL`/`ACTION_DIAL`/`tel:`) — ADR-0006 §1:
     * ثقة عالية بالرقم والاتجاه من سياقنا، والنتيجة `ATTEMPTED` فقط؛ المحاولة ليست دليل
     * محادثة مكتملة، والنهاية والمدة تبقيان مجهولتين حتى تُرصد إشارة موثوقة.
     *
     * `association = AUTO` عندما تتوفر هوية جهة الاتصال من سياق بدء المكالمة داخل التطبيق.
     */
    fun classifyInAppDialIntent(
        eventId: String,
        remoteNumber: String,
        timestampMillis: Long,
        contactId: Long? = null,
        simSlot: Int? = null,
        dedupeKey: String? = null,
    ): CallEvent = CallEvent(
        id = eventId,
        primarySource = CallSource.IN_APP,
        evidenceSources = setOf(CallSource.IN_APP),
        timestamp = timestampMillis,
        remoteNumber = remoteNumber,
        contactId = contactId,
        direction = CallDirection.OUT,
        startedAt = timestampMillis,
        outcome = CallOutcome.ATTEMPTED,
        association = if (contactId != null) CallAssociation.AUTO else CallAssociation.UNASSOCIATED,
        confirmation = CallConfirmation.PENDING,
        confidence = CallConfidence.HIGH,
        dedupeKey = dedupeKey,
        simSlot = simSlot,
    )

    /**
     * تصنيف نهاية مكالمة `IN_APP` من انتقال الهاتف `OFFHOOK→IDLE` — ADR-0006 §1:
     * المدة **تقريبية** ولا تثبت أن المكالمة رُدّ عليها، لذا النتيجة `CONNECTED` بثقة
     * `MEDIUM` — **مطلقاً لا `COMPLETED`**؛ تأكيد المستخدم هو المسار الوحيد للاكتمال
     * بلا دليل منصة.
     *
     * يُطبق فقط على حدث `IN_APP` في حالة `ATTEMPTED` وإذا كانت نافذة الانتقال موجبة؛
     * وإلا يُعاد الحدث كما هو دون أي اختلاق (fail-safe).
     */
    fun onInAppCallEnded(observed: CallEvent, offhookAtMillis: Long, idleAtMillis: Long): CallEvent {
        if (observed.primarySource != CallSource.IN_APP) return observed
        if (observed.outcome != CallOutcome.ATTEMPTED) return observed
        if (idleAtMillis <= offhookAtMillis) return observed
        val approximateDurationSec = ((idleAtMillis - offhookAtMillis) / 1000L).toInt()
        return observed.copy(
            connectedAt = offhookAtMillis,
            endedAt = idleAtMillis,
            durationSec = approximateDurationSec,
            outcome = CallOutcome.CONNECTED,
            confidence = CallConfidence.MEDIUM,
        )
    }

    /**
     * حسم إجابة التأكيد الأربعية على حدث (ADR-0006 §5):
     * - «تم» ← `CONFIRMED` + `COMPLETED` + ثقة `USER_CONFIRMED` (يؤهل لتحديث أرحام عند الربط).
     * - «لم يرد» ← `CONFIRMED` + `UNANSWERED` (لا يحدّث أرحام أبداً).
     * - «غير متعلقة» ← `REJECTED` مع بقاء باقي حقول الحدث كما هي.
     * - «لاحقاً» ← لا تغيير إطلاقاً؛ يبقى `PENDING`.
     *
     * في كل إجابة حاسمة يُضاف `MANUAL` إلى `evidenceSources` — التأكيد اليدوي يضيف دليلاً
     * ولا يستبدل المصدر الأول (ADR-0006 §5)؛ ربطُ جهة الاتصال فعلٌ مستقل لا يحدث هنا.
     */
    fun resolveConfirmation(event: CallEvent, answer: ConfirmationAnswer): CallEvent = when (answer) {
        ConfirmationAnswer.LATER -> event
        ConfirmationAnswer.CONTACTED -> event.copy(
            confirmation = CallConfirmation.CONFIRMED,
            outcome = CallOutcome.COMPLETED,
            confidence = CallConfidence.USER_CONFIRMED,
            evidenceSources = event.evidenceSources + CallSource.MANUAL,
        )
        ConfirmationAnswer.NO_ANSWER -> event.copy(
            confirmation = CallConfirmation.CONFIRMED,
            outcome = CallOutcome.UNANSWERED,
            confidence = CallConfidence.USER_CONFIRMED,
            evidenceSources = event.evidenceSources + CallSource.MANUAL,
        )
        ConfirmationAnswer.NOT_RELATED -> event.copy(
            confirmation = CallConfirmation.REJECTED,
            evidenceSources = event.evidenceSources + CallSource.MANUAL,
        )
    }

    /**
     * تصنيف إشارة فرز المكالمات الواردة (`CallScreeningService.onScreenCall` — T8-09 · ADR-0006 §1)
     * من **بدائيات حصراً** — المنصة تعطي رقماً وحالة تحقق، ولا شيء آخر يُستنتج:
     * - المصدر `ANDROID_CALL_SCREENING` والاتجاه `IN` (الفرز للوارد حصراً — الصادر يُصمَت قبل التصنيف).
     * - النتيجة `UNKNOWN` دائماً: الفرز لا يثبت اتصالاً ولا نهاية ولا مدة — **ولا يُحدّث
     *   `lastContactedAt` أبداً** إلا بتأكيد المستخدم لاحقاً (بوابة AC-8).
     * - الربط `UNASSOCIATED` والتأكيد `PENDING`: **لا حلّ رقم→جهة** هنا — الوصول إلى جهات
     *   الاتصال من ميزة أرحام ممنوع (ADR-0007)؛ الربط مسؤولية مسار التأكيد في T8-06.
     * - الثقة `HIGH` فقط عند تحقق المنصة من الرقم (`VERIFIED`)، وإلا `LOW`.
     * - `dedupeKey = null` و`simSlot = null`: لا اختراع مفاتيح — المطابقة بالرقم والاتجاه
     *   والنافذة (قرار D-2)، والمنصة لا تعطي الشريحة في تفاصيل الفرز.
     *
     * @param remoteNumber الرقم من `handle` بعد تجريد المخطط؛ `null` = محجوب/غائب —
     *   بلا أساس منع تكرار (تُخزَّن حداً مستقلاً).
     * @param verificationPassed هل تحقق المنصة من الرقم (`VERIFICATION_STATUS_VERIFIED`).
     */
    fun classifyScreeningSignal(
        eventId: String,
        remoteNumber: String?,
        timestampMillis: Long,
        verificationPassed: Boolean,
    ): CallEvent = CallEvent(
        id = eventId,
        primarySource = CallSource.ANDROID_CALL_SCREENING,
        evidenceSources = setOf(CallSource.ANDROID_CALL_SCREENING),
        timestamp = timestampMillis,
        remoteNumber = remoteNumber,
        direction = CallDirection.IN,
        startedAt = timestampMillis,
        outcome = CallOutcome.UNKNOWN,
        association = CallAssociation.UNASSOCIATED,
        confirmation = CallConfirmation.PENDING,
        confidence = if (verificationPassed) CallConfidence.HIGH else CallConfidence.LOW,
        dedupeKey = null,
        simSlot = null,
    )
}
