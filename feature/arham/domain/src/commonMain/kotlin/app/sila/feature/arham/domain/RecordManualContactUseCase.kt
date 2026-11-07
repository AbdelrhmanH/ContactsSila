package app.sila.feature.arham.domain

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallSource

/**
 * حالة استخدام: تسجيل يدوي صريح «تواصلت معه» من بطاقة القريب (ADR-0006 §5/§6).
 *
 * التسجيل اليدوي يُعامل كـ `CONFIRMED` + `COMPLETED` بثقة `USER_CONFIRMED` ويُحدّث
 * التاريخ عبر قاعدة AC-8. إذا وُجد حدث مرصود مطابق (رقم/اتجاه/نافذة زمنية) دُمج
 * التأكيد فيه — يُحفظ المصدر الأول وتُضاف `MANUAL` للأدلة — ولا يُنشأ حدث ثانٍ؛
 * ولا يُدمج أبداً في حدث `REJECTED` (رفضُ المستخدم نهائي). تطابقٌ غامضٌ يُرفع
 * لاختيار المستخدم بلا كتابة (ADR-0006 §5).
 */
class RecordManualContactUseCase(
    private val repository: ArhamCallEventRepository,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) {

    /**
     * @param eventId معرف فريد يولّده المستدعي للحدث اليدوي (يُستخدم عند عدم وجود مطابق).
     * @param contactId جهة الاتصال المرتبطة — التسجيل اليدوي من بطاقة قريب مرتبط دائماً.
     * @param timestampMillis زمن التسجيل.
     * @param remoteNumber رقم اختياري يوسّع منع التكرار مع الأحداث المرصودة؛ بدونه لا دمج تلقائي.
     * @param direction اتجاه اختياري؛ يدويٌّ بلا اتجاه يطابق أي اتجاه في منع التكرار.
     * @param dedupeWindowMillis نافذة منع التكرار؛ الافتراضي 15 دقيقة (PROPOSED).
     * @return نتيجة الإدخال، أو خطأ `Validation` لمعرف فارغ.
     */
    suspend operator fun invoke(
        eventId: String,
        contactId: Long,
        timestampMillis: Long,
        remoteNumber: String? = null,
        direction: CallDirection? = null,
        dedupeWindowMillis: Long = CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS,
    ): AppResult<CallEventIngestion> {
        if (eventId.isBlank()) {
            return AppResult.Error(DataError.Validation(field = "eventId", reason = "معرف الحدث مطلوب"))
        }
        val manualEvent = CallEvent(
            id = eventId,
            primarySource = CallSource.MANUAL,
            evidenceSources = setOf(CallSource.MANUAL),
            timestamp = timestampMillis,
            remoteNumber = remoteNumber,
            contactId = contactId,
            direction = direction,
            outcome = CallOutcome.COMPLETED,
            association = CallAssociation.MANUAL,
            confirmation = CallConfirmation.CONFIRMED,
            confidence = CallConfidence.USER_CONFIRMED,
        )
        val match = if (remoteNumber != null || manualEvent.dedupeKey != null) {
            val candidates = repository
                .findEventsBetween(
                    fromMillis = timestampMillis - dedupeWindowMillis,
                    toMillis = timestampMillis + dedupeWindowMillis,
                )
                .filter { it.confirmation != CallConfirmation.REJECTED }
            stateMachine.findDedupeMatch(manualEvent, candidates, dedupeWindowMillis)
        } else {
            DedupeMatch.None
        }
        return when (match) {
            is DedupeMatch.Ambiguous -> AppResult.Success(CallEventIngestion.Ambiguous(match.matches))
            is DedupeMatch.None -> {
                repository.upsertEvent(manualEvent)
                applyLastContactedRule(manualEvent)
                AppResult.Success(CallEventIngestion.Persisted(manualEvent))
            }
            is DedupeMatch.Unique -> {
                val merged = stateMachine.merge(match.existing, manualEvent)
                val confirmed = merged.copy(
                    confirmation = CallConfirmation.CONFIRMED,
                    outcome = CallOutcome.COMPLETED,
                    confidence = CallConfidence.USER_CONFIRMED,
                    association = if (merged.association == CallAssociation.UNASSOCIATED) {
                        CallAssociation.MANUAL
                    } else {
                        merged.association
                    },
                )
                repository.upsertEvent(confirmed)
                applyLastContactedRule(confirmed)
                AppResult.Success(CallEventIngestion.Merged(confirmed))
            }
        }
    }

    private suspend fun applyLastContactedRule(event: CallEvent) {
        val contactId = event.contactId ?: return
        if (stateMachine.updatesLastContacted(event)) {
            repository.updateLastContacted(contactId = contactId, timestampMillis = event.timestamp)
        }
    }
}
