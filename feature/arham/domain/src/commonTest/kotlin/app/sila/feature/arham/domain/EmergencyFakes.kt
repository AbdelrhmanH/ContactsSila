package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.Phone
import app.sila.core.model.RingingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant

/** ساعة اختبار ثابتة القاعدة — اصطلاح T8-06/07: `1_760_000_000_000L`. */
internal class FixedEmergencyClock(var millis: Long = 1_760_000_000_000L) : AppClock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

/** منفذ طوارئ مزيّف — يسجّل الأوامر ويتيح محاكاة «المنفذ يعمل» للتسوية (§N.5). */
internal class FakeEmergencyController : EmergencyRingingController {
    val startedSessions = mutableListOf<Pair<Long, Long>>() // sessionId to contactId
    var stopCount = 0
    private var runningSessionId: Long? = null

    override fun start(sessionId: Long, contactId: Long) {
        startedSessions += sessionId to contactId
        runningSessionId = sessionId
    }

    override fun stop() {
        stopCount++
        runningSessionId = null
    }

    override fun isRunning(sessionId: Long): Boolean = runningSessionId == sessionId

    /** محاكاة انقطاع العملية (sticky restart) — المنفذ لم يعد يعمل. */
    fun simulateProcessDeath() {
        runningSessionId = null
    }
}

/**
 * مستودع جلسات مزيّف فوق ذاكرة — كل القواعد المفردة (عدّ النافذة، الجارية الوحيدة،
 * محاولة ذرية، حسم idempotent) بمحتوى دلالي مطابق لعقد الـ DAO (§N) بلا Room.
 */
internal class FakeEmergencySessionRepository(
    private val clock: FixedEmergencyClock,
    contactsById: Map<Long, Contact> = emptyMap(),
    private val sessionsStartedInWindow: Int = 0,
) : EmergencySessionRepository {

    val contactsById = contactsById.toMutableMap()
    val finishedStatuses = mutableListOf<Pair<Long, RingingStatus>>() // sessionId to status
    var nextSessionId = 100L
    var liveSession: EmergencyRingingSession? = null
    var recordAttemptReturnsNull = false

    override suspend fun countSessionsStartedSince(cutoffMillis: Long): Int = sessionsStartedInWindow

    override suspend fun findInProgressSession(): EmergencyRingingSession? = liveSession

    override fun observeInProgressSession(): Flow<EmergencyRingingSession?> = flowOf(liveSession)

    override suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long {
        check(liveSession == null) { "DAO contract: single IN_PROGRESS row" }
        val id = nextSessionId++
        liveSession = EmergencyRingingSession(
            id = id,
            contactId = contactId,
            contactName = contactsById[contactId]?.name?.displayName.orEmpty(),
            phoneE164 = contactsById[contactId]?.phones?.firstOrNull()?.e164.orEmpty(),
            attempts = 0,
            status = RingingStatus.IN_PROGRESS,
            startedAt = startedAtMillis,
            lastAttemptAt = startedAtMillis,
        )
        return id
    }

    override suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int? {
        val session = liveSession?.takeIf { it.id == sessionId && it.status == RingingStatus.IN_PROGRESS }
            ?: return null
        if (recordAttemptReturnsNull) return null
        val updated = session.copy(attempts = session.attempts + 1, lastAttemptAt = atMillis)
        liveSession = updated
        return updated.attempts
    }

    override suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean {
        val session = liveSession?.takeIf { it.id == sessionId } ?: return false
        // idempotent (§N.8): حسمٌ ثانٍ لا يعيد الكتابة.
        if (session.status != RingingStatus.IN_PROGRESS) return false
        liveSession = session.copy(status = status, lastAttemptAt = atMillis)
        finishedStatuses += sessionId to status
        return true
    }

    override suspend fun getContactById(contactId: Long): Contact? = contactsById[contactId]
}

/** جهة اختبار — طوارئ افتراضياً (بوابة الفئة AC-14). */
internal fun emergencyTestContact(
    id: Long = 42L,
    category: Category = Category.EMERGENCY,
    isBlocked: Boolean = false,
): Contact = Contact(
    id = id,
    systemContactId = "sys-$id",
    name = ContactName(displayName = "العم أحمد"),
    phones = listOf(Phone(raw = "+201234567890", e164 = "+201234567890", isPrimary = true)),
    category = category,
    isBlocked = isBlocked,
)
