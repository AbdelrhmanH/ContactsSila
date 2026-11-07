package app.sila.feature.arham.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات سياسة جلسات الطوارئ (T8-10 · §N.2/§N.4/§N.5 · Issue #29) — القواعد الثلاث
 * صرفاً بلا تبعيات: حد المعدل 3/24h بالقطع الصارم، سقف المحاولات 10، وحد الحيوية 30 دقيقة.
 */
class EmergencySessionPolicyTest {

    private val policy = EmergencySessionPolicy()

    // ---- حد المعدل: strict `<` (§N.2) ----

    @Test
    fun canStart_allowsBelowLimit() {
        assertTrue(policy.canStartSession(sessionsStartedInWindow = 0))
        assertTrue(policy.canStartSession(sessionsStartedInWindow = 2))
    }

    @Test
    fun canStart_rejectsAtLimitExactly_strictLessThan() {
        // 3 بدأت ضمن النافذة ⇒ النفسجة الرابعة مرفوضة (بدون `<` الصارم كانت ستمر).
        assertFalse(policy.canStartSession(sessionsStartedInWindow = 3))
        assertFalse(policy.canStartSession(sessionsStartedInWindow = 4))
    }

    // ---- سقف المحاولات: 10 لكل جلسة (§C.1-8) ----

    @Test
    fun attemptCeiling_allowsNine_rejectsTen() {
        assertFalse(policy.isAttemptCeilingReached(attemptsAfterRecord = 9))
        assertTrue(policy.isAttemptCeilingReached(attemptsAfterRecord = 10))
        assertTrue(policy.isAttemptCeilingReached(attemptsAfterRecord = 11))
    }

    // ---- حد الحيوية: 30 دقيقة بالقطع الصارم `>` (§N.5 — مُقترَح، موثّق كجسر INTERRUPTED) ----

    @Test
    fun stale_failsOnlyAfterLivenessBound() {
        val lastAttempt = 1_760_000_000_000L
        val bound = policy.sessionLivenessBoundMillis
        // عند الحد تماماً: ليست عالقة (قطعية صارمة).
        assertFalse(policy.isStaleInProgress(lastAttemptAtMillis = lastAttempt, nowMillis = lastAttempt + bound))
        // الحد + 1ms: عالقة.
        assertTrue(
            policy.isStaleInProgress(lastAttemptAtMillis = lastAttempt, nowMillis = lastAttempt + bound + 1),
        )
        // قبل الحد: ليست عالقة.
        assertFalse(
            policy.isStaleInProgress(lastAttemptAtMillis = lastAttempt, nowMillis = lastAttempt + bound - 1),
        )
    }

    @Test
    fun rateLimitWindow_is24h() {
        assertEquals(24L * 60L * 60L * 1000L, policy.rateLimitWindowMillis)
    }
}
