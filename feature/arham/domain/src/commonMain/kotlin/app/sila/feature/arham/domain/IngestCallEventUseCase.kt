package app.sila.feature.arham.domain

import app.sila.core.model.CallEvent

/**
 * حالة استخدام: إدخال حدث مكالمة مرصود في آلة الحالات (ADR-0006 §دورة حياة).
 *
 * يطبق منع التكرار قبل التخزين: التطابق الفريد يُدمج في الحدث القائم، والغموض
 * يُرفع للمستخدم بلا كتابة، ولا يُحدَّث `lastContactedAt` إلا باستيفاء قاعدة
 * AC-8 (`CallEventStateMachine.updatesLastContacted`) — آلة الحالات هي الكاتب
 * الوحيد لآخر تواصل من أحداث المكالمات.
 */
class IngestCallEventUseCase(
    private val repository: ArhamCallEventRepository,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) {

    /**
     * @param candidate الحدث المرشّح المُصنّف من إشارة المنصة (معرفٌه يولّده المستدعي).
     * @param dedupeWindowMillis نافذة منع التكرار؛ الافتراضي 15 دقيقة (PROPOSED).
     */
    suspend operator fun invoke(
        candidate: CallEvent,
        dedupeWindowMillis: Long = CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS,
    ): CallEventIngestion {
        val hasDedupeBasis = candidate.remoteNumber != null || candidate.dedupeKey != null
        val match = if (hasDedupeBasis) {
            val existing = repository.findEventsBetween(
                fromMillis = candidate.timestamp - dedupeWindowMillis,
                toMillis = candidate.timestamp + dedupeWindowMillis,
            )
            stateMachine.findDedupeMatch(candidate, existing, dedupeWindowMillis)
        } else {
            DedupeMatch.None
        }
        return when (match) {
            is DedupeMatch.Ambiguous -> CallEventIngestion.Ambiguous(match.matches)
            is DedupeMatch.None -> {
                repository.upsertEvent(candidate)
                applyLastContactedRule(candidate)
                CallEventIngestion.Persisted(candidate)
            }
            is DedupeMatch.Unique -> {
                val merged = stateMachine.merge(match.existing, candidate)
                repository.upsertEvent(merged)
                applyLastContactedRule(merged)
                CallEventIngestion.Merged(merged)
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
