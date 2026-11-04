package app.sila.feature.arham

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.dao.LastContactedUpdate
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.domain.ArhamCallEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات تنفيذ مستودع أحداث المكالمات (T8-03 · Issue #29):
 * - **بوابة AC-8 على حدود النطاق/البيانات:** تقييم الأهلية عبر آلة الحالات نفسها —
 *   الحدث المؤهَّل يمرّر تحديثاً ذرّياً مصاحباً، و`REJECTED` والناقص الدليل يمرّران
 *   تحديثاً فارغاً (آخر تواصل لا يُلمس — قيود C4/T8-01 F-2).
 * - **ذرّية المسار الكامل مع قاعدة حية:** حدث مؤهَّل عبر المستودع يُخزَّن مع رفع
 *   `lastContactedAt` في معاملة واحدة؛ وحدث مرفوض يُخزَّن وحده.
 * - الاستثناءات تنتشر بلا ابتلاع (عقد F-5 — المستودع لا يُخفق بصمت).
 */
class ArhamCallEventRepositoryImplTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val now = 1_800_000_000_000L

    /** عقد ملتقط — يسجّل الكتابات المركّبة وتحديثات آخر تواصل دون قاعدة حية. */
    private class RecordingArhamDao : ArhamDao {
        val upsertedEvents = mutableListOf<CallEvent>()
        val compositeUpdates = mutableListOf<LastContactedUpdate?>()
        val lastContactedWrites = mutableListOf<LastContactedUpdate>()

        var nextEvent: CallEvent? = null
        var nextEvents: List<CallEvent> = emptyList()
        var failure: Throwable? = null

        override fun observeArhamContacts(): Flow<List<Contact>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit

        override suspend fun recordContactCall(contactId: Long, timestampMillis: Long) = Unit

        override suspend fun getCallEventById(eventId: String): CallEvent? {
            failure?.let { throw it }
            return nextEvent
        }

        override suspend fun findCallEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> {
            failure?.let { throw it }
            return nextEvents
        }

        override suspend fun upsertCallEvent(event: CallEvent, lastContactedUpdate: LastContactedUpdate?) {
            failure?.let { throw it }
            upsertedEvents += event
            compositeUpdates += lastContactedUpdate
        }

        override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
            failure?.let { throw it }
            lastContactedWrites += LastContactedUpdate(contactId, timestampMillis)
        }
    }

    /** حدث مكتمل بالدليل المنصي الكامل (مؤهَّل للقاعدة عند ربطه بجهة). */
    private fun evidencedCompletionEvent(
        id: String = "ev-1",
        contactId: Long? = 7L,
        confirmation: CallConfirmation = CallConfirmation.PENDING,
    ) = CallEvent(
        id = id,
        primarySource = CallSource.IN_APP,
        evidenceSources = setOf(CallSource.IN_APP),
        timestamp = now,
        remoteNumber = "+201000000000",
        contactId = contactId,
        direction = CallDirection.OUT,
        startedAt = now - 60_000L,
        connectedAt = now - 50_000L,
        endedAt = now,
        durationSec = 50,
        outcome = CallOutcome.COMPLETED,
        association = if (contactId != null) CallAssociation.AUTO else CallAssociation.UNASSOCIATED,
        confirmation = confirmation,
        confidence = CallConfidence.HIGH,
    )

    // ===== بوابة AC-8 عبر آلة الحالات (التقاط/التخطيط بلا قاعدة حية) =====

    @Test
    fun upsertEvent_eligibleEvidencedCompletion_passesAtomicUpdatePayload() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)
        val event = evidencedCompletionEvent()

        repo.upsertEvent(event)

        assertEquals(listOf(event), dao.upsertedEvents)
        val update = assertNotNull(
            dao.compositeUpdates.single(),
            "الحدث المؤهَّل يحمل تحديثاً مصاحباً — المعاملة الذرّية في الواجهة",
        )
        assertEquals(7L, update.contactId)
        assertEquals(now, update.timestampMillis)
    }

    @Test
    fun upsertEvent_rejectedEvent_updatePayloadIsNull_neverTouchesLastContacted() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)
        // رفض المستخدم يعلو على كل دليل منصة — حدث بكامل الأدلة و REJECTED.
        val rejected = evidencedCompletionEvent(confirmation = CallConfirmation.REJECTED)

        repo.upsertEvent(rejected)

        assertEquals(listOf(rejected), dao.upsertedEvents, "الحدث المرفوض يُخزَّن بصف مستقل")
        assertNull(
            dao.compositeUpdates.single(),
            "قاعدة AC-8: REJECTED لا يحدّث آخر تواصل أبداً حتى بكامل الأدلة",
        )
        assertTrue(dao.lastContactedWrites.isEmpty())
    }

    @Test
    fun upsertEvent_ineligibleOutcomes_updatePayloadIsNull() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)

        // مصفوفة الاستبعاد: محاولة؛ اتصال بلا نهاية؛ لم يرد؛ غير معروف؛ مؤكد بلا اكتمال.
        val cases = listOf(
            evidencedCompletionEvent(id = "ev-attempted").copy(
                connectedAt = null, endedAt = null, durationSec = null, outcome = CallOutcome.ATTEMPTED,
            ),
            evidencedCompletionEvent(id = "ev-connected-no-end").copy(
                endedAt = null, durationSec = null, outcome = CallOutcome.CONNECTED,
            ),
            evidencedCompletionEvent(id = "ev-unanswered").copy(
                outcome = CallOutcome.UNANSWERED, durationSec = 0,
            ),
            evidencedCompletionEvent(id = "ev-unknown").copy(
                outcome = CallOutcome.UNKNOWN,
            ),
            evidencedCompletionEvent(id = "ev-confirmed-not-completed").copy(
                outcome = CallOutcome.CONNECTED, confirmation = CallConfirmation.CONFIRMED,
                confidence = CallConfidence.USER_CONFIRMED,
            ),
        )

        cases.forEach { event -> repo.upsertEvent(event) }

        assertEquals(cases.size, dao.upsertedEvents.size, "كل الأحداث تُخزَّن كصفوف مستقلة")
        assertTrue(
            dao.compositeUpdates.all { it == null },
            "قاعدة AC-8: الناقص/غير المؤهَّل لا يرفع آخر تواصل (منع التضخيم الزائف)",
        )
        assertTrue(dao.lastContactedWrites.isEmpty())
    }

    @Test
    fun upsertEvent_withoutContactId_updatePayloadIsNull() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)
        val unlinked = evidencedCompletionEvent(contactId = null)

        repo.upsertEvent(unlinked)

        assertNull(dao.compositeUpdates.single(), "حدث غير مرتبط بجهة: لا وجهة لتحديث آخر تواصل")
    }

    @Test
    fun updateLastContacted_delegatesToDao_verbatim() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)

        repo.updateLastContacted(contactId = 42L, timestampMillis = now)

        assertEquals(listOf(LastContactedUpdate(42L, now)), dao.lastContactedWrites)
    }

    @Test
    fun reads_delegateToDao() = runTest {
        val dao = RecordingArhamDao()
        val repo: ArhamCallEventRepository = ArhamCallEventRepositoryImpl(arhamDao = dao)
        val stored = evidencedCompletionEvent()
        dao.nextEvent = stored
        dao.nextEvents = listOf(stored)

        assertEquals(stored, repo.getEvent("ev-1"))
        assertEquals(listOf(stored), repo.findEventsBetween(now - 1, now + 1))
    }

    @Test
    fun repositoryExceptions_propagate_withoutSwallowing() = runTest {
        val dao = RecordingArhamDao()
        val repo = ArhamCallEventRepositoryImpl(arhamDao = dao)
        dao.failure = IllegalStateException("فشل قاعدة حي — ينتشر بلا ابتلاع (عقد F-5)")

        assertFailsWith<IllegalStateException> { repo.upsertEvent(evidencedCompletionEvent()) }
        assertFailsWith<IllegalStateException> { repo.getEvent("ev-1") }
        assertFailsWith<IllegalStateException> { repo.findEventsBetween(0, 1) }
        assertFailsWith<IllegalStateException> { repo.updateLastContacted(1L, now) }
    }

    // ===== التكامل مع قاعدة حية (مسار المستودع → الواجهة كاملاً) =====

    @Test
    fun upsertEvent_eligible_endToEnd_eventAndLastContactedPersist() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "sys_endtoend",
                    name = ContactName(displayName = "الخالة سعاد"),
                    phones = listOf(Phone(raw = "01333333333", e164 = "+201333333333", isPrimary = true)),
                    category = Category.FAMILY,
                    arham = ArhamInfo(relation = Relation.AUNT_MATERNAL, frequency = ArhamFrequency.WEEKLY),
                    normalizedName = "الخالة سعاد"
                )
            )
            val contactId = db.observeArhamContacts().first().single().id
            val repo = ArhamCallEventRepositoryImpl(arhamDao = db)

            val event = CallEvent(
                id = "ev-e2e",
                primarySource = CallSource.IN_APP,
                evidenceSources = setOf(CallSource.IN_APP),
                timestamp = now,
                remoteNumber = "+201333333333",
                contactId = contactId,
                direction = CallDirection.OUT,
                startedAt = now - 60_000L,
                connectedAt = now - 50_000L,
                endedAt = now,
                durationSec = 50,
                outcome = CallOutcome.COMPLETED,
                association = CallAssociation.AUTO,
                confirmation = CallConfirmation.PENDING,
                confidence = CallConfidence.HIGH,
            )
            repo.upsertEvent(event)
            // الاستدعاء اللاحق للنطاق (idempotent بلا أثر دلالي — عقد المسارين).
            repo.updateLastContacted(contactId, event.timestamp)

            assertEquals(event, db.getCallEventById("ev-e2e"))
            val arham = assertNotNull(db.getContactById(contactId), "الجهة في الكاش").arham
            assertEquals(now, arham?.lastContactedAt, "قاعدة AC-8 عبر المستودع: آخر تواصل = وقت الحدث")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertEvent_rejected_endToEnd_eventPersists_lastContactedUntouched() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "sys_rejected",
                    name = ContactName(displayName = "ابن الخالة"),
                    phones = listOf(Phone(raw = "01444444444", e164 = "+201444444444", isPrimary = true)),
                    category = Category.FAMILY,
                    arham = ArhamInfo(relation = Relation.COUSIN_MATERNAL, frequency = ArhamFrequency.MONTHLY),
                    normalizedName = "ابن الخالة"
                )
            )
            val contactId = db.observeArhamContacts().first().single().id
            val repo = ArhamCallEventRepositoryImpl(arhamDao = db)

            val rejected = CallEvent(
                id = "ev-rejected-e2e",
                primarySource = CallSource.IN_APP,
                evidenceSources = setOf(CallSource.IN_APP, CallSource.MANUAL),
                timestamp = now,
                remoteNumber = "+201444444444",
                contactId = contactId,
                direction = CallDirection.OUT,
                startedAt = now - 60_000L,
                connectedAt = now - 50_000L,
                endedAt = now,
                durationSec = 50,
                outcome = CallOutcome.COMPLETED,
                association = CallAssociation.AUTO,
                confirmation = CallConfirmation.REJECTED,
                confidence = CallConfidence.HIGH,
            )
            repo.upsertEvent(rejected)

            assertEquals(rejected, db.getCallEventById("ev-rejected-e2e"), "الحدث المرفوض محفوظ بصف مستقل")
            assertNull(
                db.getContactById(contactId)?.arham?.lastContactedAt,
                "قاعدة AC-8: REJECTED لا يحدّث آخر تواصل حتى في المسار الكامل",
            )
        } finally {
            db.closeConnection()
        }
    }
}
