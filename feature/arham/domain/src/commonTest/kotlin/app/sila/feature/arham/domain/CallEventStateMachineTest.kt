package app.sila.feature.arham.domain

import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * اختبارات آلة حالات أحداث المكالمات (ADR-0006):
 * مصفوفة `outcome × confirmation` الجدولية الكاملة مقابل قاعدة `lastContactedAt` (AC-8)،
 * ومنع التكرار والدمج، وتصنيف إشارات `IN_APP`، وحسم التأكيد الأربعية.
 */
class CallEventStateMachineTest {

    private val machine = CallEventStateMachine()

    // region قاعدة تحديث أرحام (AC-8)

    /** حالة جدولية واحدة من مصفوفة AC-8. */
    private data class Ac8Case(
        val label: String,
        val outcome: CallOutcome,
        val confirmation: CallConfirmation,
        val connectedAt: Long? = null,
        val endedAt: Long? = null,
        val durationSec: Int? = null,
        val contactId: Long? = 1L,
        val expected: Boolean,
    )

    private fun ac8Event(case: Ac8Case) = CallEvent(
        id = "ac8",
        primarySource = CallSource.IN_APP,
        evidenceSources = setOf(CallSource.IN_APP),
        timestamp = 1_000L,
        contactId = case.contactId,
        connectedAt = case.connectedAt,
        endedAt = case.endedAt,
        durationSec = case.durationSec,
        outcome = case.outcome,
        confirmation = case.confirmation,
    )

    private fun assertAc8(case: Ac8Case) {
        assertEquals(
            case.expected,
            machine.updatesLastContacted(ac8Event(case)),
            "AC-8 mismatch: ${case.label}",
        )
    }

    @Test
    fun updatesLastContacted_fullOutcomeConfirmationMatrix_withCompleteEvidence() {
        // كل توليفة من الخمس نتائج × أربع حالات تأكيد، بأدلة منصة كاملة + جهة اتصال:
        // يتأهل فقط COMPLETED غير المرفوض — بالأدلة أو بتأكيد المستخدم (AC-8).
        for (outcome in CallOutcome.entries) {
            for (confirmation in CallConfirmation.entries) {
                val case = Ac8Case(
                    label = "outcome=$outcome confirmation=$confirmation",
                    outcome = outcome,
                    confirmation = confirmation,
                    connectedAt = 1_000L,
                    endedAt = 61_000L,
                    durationSec = 60,
                    expected = outcome == CallOutcome.COMPLETED && confirmation != CallConfirmation.REJECTED,
                )
                assertAc8(case)
            }
        }
    }

    @Test
    fun updatesLastContacted_excludedStates_neverUpdate_evenWhenConfirmed() {
        // ATTEMPTED وCONNECTED وUNANSWERED وUNKNOWN لا تُحدّث حتى بتأكيد المستخدم —
        // إجابة «لم يرد» (CONFIRMED+UNANSWERED) مثلاً لا تلمس آخر تواصل.
        listOf(
            Ac8Case("ATTEMPTED+CONFIRMED", CallOutcome.ATTEMPTED, CallConfirmation.CONFIRMED, expected = false),
            Ac8Case("CONNECTED+CONFIRMED", CallOutcome.CONNECTED, CallConfirmation.CONFIRMED, expected = false),
            Ac8Case("UNANSWERED+CONFIRMED", CallOutcome.UNANSWERED, CallConfirmation.CONFIRMED, expected = false),
            Ac8Case("UNKNOWN+CONFIRMED", CallOutcome.UNKNOWN, CallConfirmation.CONFIRMED, expected = false),
        ).forEach(::assertAc8)
    }

    @Test
    fun updatesLastContacted_rejectedCompletionWithFullEvidence_neverUpdates() {
        // رفضُ المستخدم («غير متعلقة») يعلو على دليل المنصة — خطوة T8 §D.3-4:
        // «REJECTED must never update it» حتى مع اتصال ونهاية ومدة كاملة.
        assertAc8(
            Ac8Case(
                label = "REJECTED+بالأدلة الكاملة",
                outcome = CallOutcome.COMPLETED,
                confirmation = CallConfirmation.REJECTED,
                connectedAt = 1_000L,
                endedAt = 61_000L,
                durationSec = 60,
                expected = false,
            ),
        )
    }

    @Test
    fun updatesLastContacted_completedPending_requiresEveryEvidencePiece() {
        // الاكتمال بلا تأكيد يتطلب اتصالاً ونهاية ومدة > 0 — أي ثغرة تلغي الأهلية.
        listOf(
            Ac8Case("بلا اتصال", CallOutcome.COMPLETED, CallConfirmation.PENDING, endedAt = 61_000L, durationSec = 60, expected = false),
            Ac8Case("بلا نهاية", CallOutcome.COMPLETED, CallConfirmation.PENDING, connectedAt = 1_000L, durationSec = 60, expected = false),
            Ac8Case("بلا مدة", CallOutcome.COMPLETED, CallConfirmation.PENDING, connectedAt = 1_000L, endedAt = 61_000L, expected = false),
            Ac8Case("مدة صفر", CallOutcome.COMPLETED, CallConfirmation.PENDING, connectedAt = 1_000L, endedAt = 61_000L, durationSec = 0, expected = false),
            Ac8Case("بلا أدلة إطلاقاً", CallOutcome.COMPLETED, CallConfirmation.PENDING, expected = false),
        ).forEach(::assertAc8)
    }

    @Test
    fun updatesLastContacted_confirmedCompletion_withoutEvidence_updates() {
        // تأكيد المستخدم الصريح يرفع الحدث حتى بلا دليل منصة (ADR-0006 §6).
        assertAc8(
            Ac8Case(
                label = "CONFIRMED+COMPLETED بلا أدلة",
                outcome = CallOutcome.COMPLETED,
                confirmation = CallConfirmation.CONFIRMED,
                expected = true,
            ),
        )
    }

    @Test
    fun updatesLastContacted_unlinkedEvent_neverUpdates() {
        // بلا جهة اتصال لا يوجد أرحام ليُحدَّث — حتى CONFIRMED+COMPLETED.
        assertAc8(
            Ac8Case(
                label = "CONFIRMED+COMPLETED بلا جهة",
                outcome = CallOutcome.COMPLETED,
                confirmation = CallConfirmation.CONFIRMED,
                contactId = null,
                expected = false,
            ),
        )
    }

    // endregion

    // region منع التكرار

    private fun observedEvent(
        id: String,
        timestamp: Long,
        remoteNumber: String? = "+201000000001",
        direction: CallDirection? = CallDirection.OUT,
        dedupeKey: String? = null,
        source: CallSource = CallSource.ANDROID_CALL_SCREENING,
    ) = CallEvent(
        id = id,
        primarySource = source,
        evidenceSources = setOf(source),
        timestamp = timestamp,
        remoteNumber = remoteNumber,
        direction = direction,
        dedupeKey = dedupeKey,
    )

    @Test
    fun matchesDedupe_sameNumberSameDirectionWithinWindow_matches() {
        val candidate = observedEvent("a", timestamp = 100_000L)
        val existing = observedEvent("b", timestamp = 100_000L + CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS)
        assertTrue(machine.matchesDedupe(candidate, existing))
    }

    @Test
    fun matchesDedupe_windowBoundaryIsInclusive() {
        val candidate = observedEvent("a", timestamp = 0L)
        val window = CallEventStateMachine.DEFAULT_DEDUPE_WINDOW_MILLIS
        assertTrue(machine.matchesDedupe(candidate, observedEvent("b", timestamp = window)))
        assertFalse(machine.matchesDedupe(candidate, observedEvent("b", timestamp = window + 1)))
    }

    @Test
    fun matchesDedupe_differentNumber_neverMatches() {
        val candidate = observedEvent("a", timestamp = 0L, remoteNumber = "+201000000001")
        val other = observedEvent("b", timestamp = 0L, remoteNumber = "+201000000002")
        assertFalse(machine.matchesDedupe(candidate, other))
    }

    @Test
    fun matchesDedupe_missingNumberOnEitherSide_neverMatches() {
        // الرقم الفارغ ليس «رقماً متطابقاً» — لا يُستنتج ولا يُخترع.
        val numbered = observedEvent("a", timestamp = 0L, remoteNumber = "+201000000001")
        val numberless = observedEvent("b", timestamp = 0L, remoteNumber = null)
        assertFalse(machine.matchesDedupe(numbered, numberless))
        assertFalse(machine.matchesDedupe(numberless, numbered))
        assertFalse(machine.matchesDedupe(numberless, observedEvent("c", timestamp = 0L, remoteNumber = null)))
    }

    @Test
    fun matchesDedupe_conflictingKnownDirections_neverMatches() {
        val outgoing = observedEvent("a", timestamp = 0L, direction = CallDirection.OUT)
        val incoming = observedEvent("b", timestamp = 0L, direction = CallDirection.IN)
        assertFalse(machine.matchesDedupe(outgoing, incoming))
    }

    @Test
    fun matchesDedupe_unknownDirectionOnEitherSide_matches() {
        val outgoing = observedEvent("a", timestamp = 0L, direction = CallDirection.OUT)
        val directionless = observedEvent("b", timestamp = 0L, direction = null)
        assertTrue(machine.matchesDedupe(outgoing, directionless))
        assertTrue(machine.matchesDedupe(directionless, outgoing))
    }

    @Test
    fun matchesDedupe_equalDedupeKey_overridesNumberDifference() {
        val candidate = observedEvent("a", timestamp = 0L, remoteNumber = "+201000000001", dedupeKey = "k1")
        val other = observedEvent("b", timestamp = 10L * 60L * 1000L, remoteNumber = "+209999999999", dedupeKey = "k1")
        assertTrue(machine.matchesDedupe(candidate, other))
    }

    @Test
    fun matchesDedupe_differentDedupeKeys_stillMatchViaNumberPath() {
        // المفتاح دليل كافٍ لا ضروري — مسار الرقم/النافذة يظل معتمداً.
        val candidate = observedEvent("a", timestamp = 0L, dedupeKey = "k1")
        val other = observedEvent("b", timestamp = 0L, dedupeKey = "k2")
        assertTrue(machine.matchesDedupe(candidate, other))
    }

    @Test
    fun matchesDedupe_sameId_isSelfNotDuplicate() {
        val event = observedEvent("a", timestamp = 0L)
        assertFalse(machine.matchesDedupe(event, event.copy()))
    }

    @Test
    fun findDedupeMatch_reportsNoneUniqueAndAmbiguous() {
        val candidate = observedEvent("c", timestamp = 0L)

        assertEquals(DedupeMatch.None, machine.findDedupeMatch(candidate, emptyList()))

        val single = listOf(observedEvent("b1", timestamp = 0L))
        val unique = machine.findDedupeMatch(candidate, single)
        assertIs<DedupeMatch.Unique>(unique)
        assertEquals("b1", unique.existing.id)

        // تطابقان غامضان — الاختيار للمستخدم (ADR-0006 §5) وحد الحالة في T8 §P.
        val ambiguous = machine.findDedupeMatch(
            candidate,
            listOf(observedEvent("b1", timestamp = 0L), observedEvent("b2", timestamp = 1_000L)),
        )
        assertIs<DedupeMatch.Ambiguous>(ambiguous)
        assertEquals(setOf("b1", "b2"), ambiguous.matches.map { it.id }.toSet())
    }

    // endregion

    // region الدمج

    @Test
    fun merge_preservesFirstPrimarySource_andUnionsEvidence() {
        val existing = observedEvent("b", timestamp = 0L, source = CallSource.ANDROID_CALL_SCREENING)
        val incoming = observedEvent("a", timestamp = 5_000L, source = CallSource.ANDROID_TELEPHONY_OBSERVER)
        val merged = machine.merge(existing, incoming)
        assertEquals("b", merged.id)
        assertEquals(CallSource.ANDROID_CALL_SCREENING, merged.primarySource)
        assertEquals(
            setOf(CallSource.ANDROID_CALL_SCREENING, CallSource.ANDROID_TELEPHONY_OBSERVER),
            merged.evidenceSources,
        )
    }

    @Test
    fun merge_keepsEarliestTimestamp() {
        val earlier = observedEvent("b", timestamp = 0L)
        val later = observedEvent("a", timestamp = 9_000L)
        assertEquals(0L, machine.merge(earlier, later).timestamp)
        assertEquals(0L, machine.merge(later, earlier).timestamp)
    }

    @Test
    fun merge_fillsNullsFromIncoming_andKeepsExistingValuesOnConflict() {
        val existing = observedEvent("b", timestamp = 0L, remoteNumber = "+201000000001")
            .copy(simSlot = 0)
        val incoming = observedEvent("a", timestamp = 1_000L, remoteNumber = "+209999999999")
            .copy(contactId = 7L, startedAt = 500L, dedupeKey = "k", simSlot = 1)
        val merged = machine.merge(existing, incoming)
        // قيم القائمة غير الفارغة لا تُستبدل:
        assertEquals("+201000000001", merged.remoteNumber)
        assertEquals(0, merged.simSlot)
        // فجوات القائمة تُملأ من المرشّح:
        assertEquals(7L, merged.contactId)
        assertEquals(500L, merged.startedAt)
        assertEquals("k", merged.dedupeKey)
    }

    @Test
    fun merge_doesNotSilentlyEscalateConfirmationOrOutcome() {
        // الدمج النقي لا يرقّي الحالة — الترقية قرار صريح عند مستدٍع مخوّل.
        val existing = observedEvent("b", timestamp = 0L).copy(outcome = CallOutcome.UNKNOWN)
        val manualConfirmation = observedEvent("a", timestamp = 1_000L, source = CallSource.MANUAL)
            .copy(
                outcome = CallOutcome.COMPLETED,
                confirmation = CallConfirmation.CONFIRMED,
                confidence = CallConfidence.USER_CONFIRMED,
            )
        val merged = machine.merge(existing, manualConfirmation)
        assertEquals(CallOutcome.UNKNOWN, merged.outcome)
        assertEquals(CallConfirmation.PENDING, merged.confirmation)
    }

    // endregion

    // region تصنيف إشارات IN_APP

    @Test
    fun classifyInAppDialIntent_recordsAttemptWithHighConfidence() {
        val event = machine.classifyInAppDialIntent(
            eventId = "dial-1",
            remoteNumber = "+201000000001",
            timestampMillis = 5_000L,
            contactId = 3L,
        )
        assertEquals("dial-1", event.id)
        assertEquals(CallSource.IN_APP, event.primarySource)
        assertEquals(setOf(CallSource.IN_APP), event.evidenceSources)
        assertEquals(CallDirection.OUT, event.direction)
        assertEquals(CallOutcome.ATTEMPTED, event.outcome)
        assertEquals(CallConfidence.HIGH, event.confidence)
        assertEquals(CallConfirmation.PENDING, event.confirmation)
        assertEquals(CallAssociation.AUTO, event.association)
        assertEquals(5_000L, event.startedAt)
        // المحاولة ليست دليل اتصال ولا اكتمال:
        assertEquals(null, event.connectedAt)
        assertEquals(null, event.endedAt)
        assertEquals(null, event.durationSec)
        // زر الاتصال لا يحدّث آخر تواصل (AC-8 / §D.3-4):
        assertFalse(machine.updatesLastContacted(event))
    }

    @Test
    fun classifyInAppDialIntent_withoutContactContext_isUnassociated() {
        val event = machine.classifyInAppDialIntent(
            eventId = "dial-2",
            remoteNumber = "+201000000001",
            timestampMillis = 5_000L,
        )
        assertEquals(CallAssociation.UNASSOCIATED, event.association)
        assertEquals(null, event.contactId)
    }

    @Test
    fun onInAppCallEnded_classifiesConnectedWithApproximateDuration_neverCompleted() {
        val dialed = machine.classifyInAppDialIntent(
            eventId = "dial-3",
            remoteNumber = "+201000000001",
            timestampMillis = 0L,
        )
        val ended = machine.onInAppCallEnded(dialed, offhookAtMillis = 10_000L, idleAtMillis = 70_500L)
        assertEquals(CallOutcome.CONNECTED, ended.outcome)
        assertEquals(CallConfidence.MEDIUM, ended.confidence)
        assertEquals(10_000L, ended.connectedAt)
        assertEquals(70_500L, ended.endedAt)
        assertEquals(60, ended.durationSec)
        // المدة التقريبية لا تثبت الرد — لا اكتمال ولا تحديث أرحام:
        assertEquals(CallConfirmation.PENDING, ended.confirmation)
        assertFalse(machine.updatesLastContacted(ended))
    }

    @Test
    fun onInAppCallEnded_isIdempotent_afterFirstClassification() {
        val dialed = machine.classifyInAppDialIntent(
            eventId = "dial-4",
            remoteNumber = "+201000000001",
            timestampMillis = 0L,
        )
        val ended = machine.onInAppCallEnded(dialed, offhookAtMillis = 10_000L, idleAtMillis = 40_000L)
        assertEquals(ended, machine.onInAppCallEnded(ended, offhookAtMillis = 10_000L, idleAtMillis = 40_000L))
    }

    @Test
    fun onInAppCallEnded_ignoresNonInAppOrNonAttemptedEvents_andMalformedWindows() {
        val screening = observedEvent("s1", timestamp = 0L, source = CallSource.ANDROID_CALL_SCREENING)
        assertEquals(screening, machine.onInAppCallEnded(screening, 10_000L, 40_000L))

        val dialed = machine.classifyInAppDialIntent(
            eventId = "dial-5",
            remoteNumber = "+201000000001",
            timestampMillis = 0L,
        )
        val connected = machine.onInAppCallEnded(dialed, offhookAtMillis = 10_000L, idleAtMillis = 40_000L)
        // حدث مُصنّف سابقاً لا يُعاد تصنيفه:
        assertEquals(connected, machine.onInAppCallEnded(connected, 50_000L, 90_000L))
        // نافذة غير منطقية (صفر/سالبة) لا دليل فيها — يُعاد الحدث كما هو:
        assertEquals(dialed, machine.onInAppCallEnded(dialed, offhookAtMillis = 10_000L, idleAtMillis = 10_000L))
        assertEquals(dialed, machine.onInAppCallEnded(dialed, offhookAtMillis = 40_000L, idleAtMillis = 10_000L))
    }

    // endregion

    // region حسم التأكيد الأربعية (ADR-0006 §5)

    private fun pendingEvent() = observedEvent("p1", timestamp = 0L)
        .copy(confirmation = CallConfirmation.PENDING)

    @Test
    fun resolveConfirmation_contacted_confirmsCompletedWithManualEvidence() {
        // حدث مرتبط بجهة اتصال — تأكيد «تم» يؤهل لتحديث آخر تواصل (AC-8).
        val resolved = machine.resolveConfirmation(pendingEvent().copy(contactId = 7L), ConfirmationAnswer.CONTACTED)
        assertEquals(CallConfirmation.CONFIRMED, resolved.confirmation)
        assertEquals(CallOutcome.COMPLETED, resolved.outcome)
        assertEquals(CallConfidence.USER_CONFIRMED, resolved.confidence)
        assertTrue(CallSource.MANUAL in resolved.evidenceSources)
        // المصدر الأول يبقى — التأكيد يضيف دليلاً ولا يستبدل:
        assertEquals(CallSource.ANDROID_CALL_SCREENING, resolved.primarySource)
        assertTrue(machine.updatesLastContacted(resolved))
    }

    @Test
    fun resolveConfirmation_noAnswer_confirmsUnanswered_neverUpdatesLastContacted() {
        val resolved = machine.resolveConfirmation(pendingEvent(), ConfirmationAnswer.NO_ANSWER)
        assertEquals(CallConfirmation.CONFIRMED, resolved.confirmation)
        assertEquals(CallOutcome.UNANSWERED, resolved.outcome)
        assertTrue(CallSource.MANUAL in resolved.evidenceSources)
        assertFalse(machine.updatesLastContacted(resolved))
    }

    @Test
    fun resolveConfirmation_notRelated_rejectsAndNeverUpdatesEvenWithEvidence() {
        val event = pendingEvent().copy(
            outcome = CallOutcome.COMPLETED,
            connectedAt = 1_000L,
            endedAt = 61_000L,
            durationSec = 60,
        )
        val resolved = machine.resolveConfirmation(event, ConfirmationAnswer.NOT_RELATED)
        assertEquals(CallConfirmation.REJECTED, resolved.confirmation)
        assertFalse(machine.updatesLastContacted(resolved))
    }

    @Test
    fun resolveConfirmation_later_keepsEventPendingUntouched() {
        val event = pendingEvent()
        assertEquals(event, machine.resolveConfirmation(event, ConfirmationAnswer.LATER))
    }

    // endregion
}
