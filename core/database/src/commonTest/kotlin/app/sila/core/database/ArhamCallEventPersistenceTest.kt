package app.sila.core.database

import androidx.room3.useReaderConnection
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.dao.LastContactedUpdate
import app.sila.core.database.entity.CallEventEntity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات استمرارية إدخال أحداث المكالمات فوق الواجهة (T8-03 · ADR-0006 · Issue #29):
 * - **الذرّية (قيود F-2 من مراجعة T8-01):** الحدث وتحديث `lastContactedAt` المؤهَّل
 *   معاملة IMMEDIATE واحدة — خطاف الفشل بين العمليتين يُثبت التراجع الكامل.
 * - قاعدة AC-8 على مستوى العقد الممرَّر: تحديث فارغ = لا لمس لآخر تواصل إطلاقاً.
 * - دلالات `max(...)` (فارغة ← تُضبط؛ أقدم ← تُرفع؛ أحدث ← لا تُنقص).
 * - الإخفاق المغلق الصمام في خرائط T8-03 (حدث بلا رقم / اتجاه `MISSED`) بلا أي كتابة.
 * - خرائط قراءة «التفاعلات الأخيرة» T6 (`RecentInteraction`) فوق v3.
 */
class ArhamCallEventPersistenceTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val now = 1_800_000_000_000L

    private fun arhamContact(displayName: String, e164: String) = Contact(
        id = 0L,
        systemContactId = "sys_$displayName",
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = "01000000000", e164 = e164, isPrimary = true)),
        category = Category.FAMILY,
        arham = ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.MONTHLY),
        normalizedName = displayName
    )

    /** حدث مكتمل بالدليل المنصي الكامل — مؤهَّل لقاعدة AC-8 عند ربطه بجهة. */
    private fun evidencedCompletionEvent(
        id: String = "ev-1",
        contactId: Long? = null,
        timestamp: Long = now,
        confirmation: CallConfirmation = CallConfirmation.PENDING,
        direction: CallDirection? = CallDirection.OUT,
        remoteNumber: String? = "+201000000000",
    ) = CallEvent(
        id = id,
        primarySource = CallSource.IN_APP,
        evidenceSources = setOf(CallSource.IN_APP),
        timestamp = timestamp,
        remoteNumber = remoteNumber,
        contactId = contactId,
        direction = direction,
        startedAt = timestamp - 60_000L,
        connectedAt = timestamp - 50_000L,
        endedAt = timestamp,
        durationSec = 50,
        outcome = CallOutcome.COMPLETED,
        association = if (contactId != null) CallAssociation.AUTO else CallAssociation.UNASSOCIATED,
        confirmation = confirmation,
        confidence = CallConfidence.HIGH,
    )

    private suspend fun LocalSilaDatabase.seedArhamContact(e164: String): Long {
        upsertContact(arhamContact(displayName = "العم أحمد", e164 = e164))
        return assertNotNull(
            observeArhamContacts().first().firstOrNull(),
            "الترتيب: جهة الأرحام تُبذر قبل الحدث",
        ).id
    }

    private suspend fun LocalSilaDatabase.lastContactedOf(contactId: Long): Long? =
        assertNotNull(getContactById(contactId), "الجهة في الكاش").arham?.lastContactedAt

    private suspend fun LocalSilaDatabase.callEventRowCount(): Int {
        var count = -1
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM call_events") { stmt ->
                stmt.step()
                count = stmt.getLong(0).toInt()
            }
        }
        return count
    }

    @Test
    fun upsertCallEvent_eligibleEvidencedCompletion_eventAndLastContactedBothPersisted() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val event = evidencedCompletionEvent(contactId = contactId)

            db.upsertCallEvent(event, LastContactedUpdate(contactId, event.timestamp))

            assertEquals(event, db.getCallEventById(event.id), "صف الحدث يُقرأ كاملاً من v3")
            assertEquals(
                event.timestamp,
                db.lastContactedOf(contactId),
                "قاعدة AC-8: آخر تواصل رُفع إلى وقت الحدث داخل المعاملة نفسها",
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_confirmedCompletion_updatesLastContacted_withoutPlatformEvidence() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            // تأكيد المستخدم الصريح: CONFIRMED + COMPLETED بلا أدلة منصة (المسار الثاني للقاعدة).
            val event = evidencedCompletionEvent(contactId = contactId).copy(
                connectedAt = null,
                endedAt = null,
                durationSec = null,
                confirmation = CallConfirmation.CONFIRMED,
                confidence = CallConfidence.USER_CONFIRMED,
                evidenceSources = setOf(CallSource.IN_APP, CallSource.MANUAL),
            )

            db.upsertCallEvent(event, LastContactedUpdate(contactId, event.timestamp))

            assertNotNull(db.getCallEventById(event.id))
            assertEquals(event.timestamp, db.lastContactedOf(contactId))
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_withoutUpdatePayload_lastContactedUntouched() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val existing = now - 3_600_000L
            db.updateLastContacted(contactId, existing)

            // حدث مؤهَّل شكلاً لكن التحديث الممرَّر فارغ — بوابة AC-8 قرار المُصدِر حصراً.
            val event = evidencedCompletionEvent(id = "ev-no-update", contactId = contactId)
            db.upsertCallEvent(event, lastContactedUpdate = null)

            assertNotNull(db.getCallEventById(event.id), "الحدث يُخزَّن")
            assertEquals(
                existing,
                db.lastContactedOf(contactId),
                "بلا تحديث ممرَّر: آخر تواصل لا يُلمس إطلاقاً",
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_faultBetweenOperations_rollsBackEntireTransaction() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val existing = now - 3_600_000L
            db.updateLastContacted(contactId, existing)
            val event = evidencedCompletionEvent(contactId = contactId, timestamp = now)

            db.callEventWriteFaultHook = { IllegalStateException("حقن فشل بين العمليتين (اختبار ذرّية F-2)") }
            try {
                assertFailsWith<IllegalStateException> {
                    db.upsertCallEvent(event, LastContactedUpdate(contactId, event.timestamp))
                }
            } finally {
                db.callEventWriteFaultHook = null
            }

            assertNull(db.getCallEventById(event.id), "التراجع الكامل: الحدث المكتوب أولاً لا يبقى وحده")
            assertEquals(
                existing,
                db.lastContactedOf(contactId),
                "والتحديث الثاني لم يقع — المعاملة واحدة أو لا شيء",
            )
            assertEquals(0, db.callEventRowCount(), "لا صفوف جزئية في call_events")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun updateLastContacted_maxSemantics_nullRaised_olderRaised_newerNeverDecreased() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val older = now - 10_000L
            val newer = now + 10_000L

            assertNull(db.lastContactedOf(contactId), "الافتراضي: لم يُتواصل أبداً")
            db.updateLastContacted(contactId, older)
            assertEquals(older, db.lastContactedOf(contactId), "الفارغة (−∞) تُرفع إلى القيمة الأولى")
            db.updateLastContacted(contactId, older - 1)
            assertEquals(older, db.lastContactedOf(contactId), "القيمة الأحدث قائماً لا تُنقص (idempotent)")
            db.updateLastContacted(contactId, newer)
            assertEquals(newer, db.lastContactedOf(contactId), "القيمة الأقدم تُرفع إلى الأحدث")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_withoutArhamRow_updatesNothingAndFabricatesNone() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // جهة عادية بلا أرحام — التحديث المؤهَّل لا يُنشئ صف أرحام phantom.
            db.upsertContact(
                Contact(
                    id = 0L,
                    systemContactId = "sys_plain",
                    name = ContactName(displayName = "جهة بلا أرحام"),
                    phones = listOf(Phone(raw = "01222222222", e164 = "+201222222222", isPrimary = true)),
                    normalizedName = "جهة بلا أرحام"
                )
            )
            val plainId = db.observeContacts().first().first { it.systemContactId == "sys_plain" }.id
            val event = evidencedCompletionEvent(id = "ev-plain", contactId = plainId)

            db.upsertCallEvent(event, LastContactedUpdate(plainId, event.timestamp))

            assertNotNull(db.getCallEventById(event.id), "الحدث يُخزَّن")
            assertNull(
                db.getContactById(plainId)?.arham,
                "لا يُصنع صف أرحام لجهة غير مرتبطة — لا اختلاق حالة",
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_unnumberedEvent_failsClosed_beforeAnyWrite() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val unnumbered = evidencedCompletionEvent(id = "ev-unnumbered", contactId = contactId)
                .copy(remoteNumber = null)

            assertFailsWith<IllegalArgumentException> {
                db.upsertCallEvent(unnumbered, null)
            }
            assertNull(db.getCallEventById(unnumbered.id), "الإخفاق المغلق: لا صف بمرآة e164 مختلقة")
            assertEquals(0, db.callEventRowCount())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_missedDirection_failsClosed_beforeAnyWrite() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val missed = evidencedCompletionEvent(id = "ev-missed", contactId = contactId)
                .copy(direction = CallDirection.MISSED)

            assertFailsWith<IllegalArgumentException> {
                db.upsertCallEvent(missed, null)
            }
            assertNull(db.getCallEventById(missed.id), "MISSED حصرية لسجل الاتصال القديم — لا تُكتب (F-3)")
            assertEquals(0, db.callEventRowCount())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun upsertCallEvent_sameIdReIngested_replacesSingleRow() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val first = evidencedCompletionEvent(id = "ev-dup", contactId = contactId, timestamp = now)
            db.upsertCallEvent(first, null)

            // إعادة الإدخال بمعرّف الحدث نفسه = استبدال الرصد (عقد upsert بـ id — ADR-0006 §5).
            val second = first.copy(
                durationSec = 90,
                evidenceSources = setOf(CallSource.IN_APP, CallSource.ANDROID_TELEPHONY_OBSERVER),
            )
            db.upsertCallEvent(second, LastContactedUpdate(contactId, second.timestamp))

            assertEquals(second, db.getCallEventById("ev-dup"), "النسخة الأحدث تحل محل القديمة")
            assertEquals(1, db.callEventRowCount(), "بلا صف ثانٍ — الاستبدال حسب المفتاح")
            assertEquals(second.timestamp, db.lastContactedOf(contactId))
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun findCallEventsBetween_returnsInclusiveWindow_inTimestampOrder() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val contactId = db.seedArhamContact(e164 = "+201000000000")
            val at = now - 5_000L
            db.upsertCallEvent(evidencedCompletionEvent(id = "ev-before", contactId = contactId, timestamp = at - 1), null)
            db.upsertCallEvent(evidencedCompletionEvent(id = "ev-from", contactId = contactId, timestamp = at), null)
            db.upsertCallEvent(evidencedCompletionEvent(id = "ev-to", contactId = contactId, timestamp = at + 1), null)

            val window = db.findCallEventsBetween(fromMillis = at, toMillis = at + 1)

            assertEquals(listOf("ev-from", "ev-to"), window.map { it.id }, "النافذة شاملة الطرفين بترتيب زمني")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun getCallEventById_missingEvent_returnsNull() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            assertNull(db.getCallEventById("no-such-event"))
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun getRecentCallEvents_mapsV3ColumnsToRecentInteractionContract() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // صف v3 كامل + صف بلا اتجاه (إرث MISSED المهاجر) — عقد T6 لا يعرضه بلا اختلاق اتجاه.
            db.seedCallEventsForTest(
                listOf(
                    CallEventEntity(
                        callLogId = "ev-mapped",
                        e164 = "+201000000000",
                        timestamp = now,
                        primarySource = CallSource.IN_APP,
                        evidenceSources = setOf(CallSource.IN_APP),
                        remoteNumber = "+201000000000",
                        contactId = null,
                        direction = CallDirection.OUT,
                        startedAt = null,
                        connectedAt = now - 50_000L,
                        endedAt = now,
                        durationSec = 50,
                        outcome = CallOutcome.COMPLETED,
                        association = CallAssociation.AUTO,
                        confirmation = CallConfirmation.PENDING,
                        confidence = CallConfidence.HIGH,
                        dedupeKey = null,
                        simSlot = null,
                    ),
                    CallEventEntity(
                        callLogId = "ev-null-direction",
                        e164 = "+201000000000",
                        timestamp = now - 1,
                        primarySource = CallSource.CALL_LOG,
                        evidenceSources = setOf(CallSource.CALL_LOG),
                        remoteNumber = null,
                        contactId = null,
                        direction = null,
                        startedAt = null,
                        connectedAt = null,
                        endedAt = null,
                        durationSec = 0,
                        outcome = CallOutcome.UNANSWERED,
                        association = CallAssociation.UNASSOCIATED,
                        confirmation = CallConfirmation.NOT_REQUIRED,
                        confidence = CallConfidence.LOW,
                        dedupeKey = null,
                        simSlot = null,
                    ),
                )
            )

            val interactions = db.getRecentCallEvents(listOf("+201000000000"), limit = 10)

            assertEquals(1, interactions.size, "صف الاتجاه الفارغ يُتخطى بلا اختلاق قيمة (عقد T6)")
            val mapped = interactions.single()
            assertEquals("ev-mapped", mapped.callLogId)
            assertEquals("+201000000000", mapped.e164)
            assertEquals(now, mapped.timestampMillis)
            assertEquals(CallDirection.OUT, mapped.direction)
            assertEquals(50, mapped.durationSec)
            assertEquals(
                CallSource.IN_APP,
                mapped.source,
                "خرائط T8-03: عمود المصدر v3 هو primarySource — الاسم القديم source في العقد فقط",
            )

            // المدة الفارغة تعرض 0 حسب العقد — بلا اختلاق مدة.
            db.seedCallEventsForTest(
                listOf(
                    CallEventEntity(
                        callLogId = "ev-no-duration",
                        e164 = "+209999999999",
                        timestamp = now,
                        primarySource = CallSource.IN_APP,
                        evidenceSources = setOf(CallSource.IN_APP),
                        remoteNumber = "+209999999999",
                        contactId = null,
                        direction = CallDirection.OUT,
                        startedAt = null,
                        connectedAt = null,
                        endedAt = null,
                        durationSec = null,
                        outcome = CallOutcome.ATTEMPTED,
                        association = CallAssociation.UNASSOCIATED,
                        confirmation = CallConfirmation.PENDING,
                        confidence = CallConfidence.HIGH,
                        dedupeKey = null,
                        simSlot = null,
                    )
                )
            )
            val noDuration = db.getRecentCallEvents(listOf("+209999999999"), limit = 10).single()
            assertEquals(0, noDuration.durationSec)
            assertTrue(noDuration.callLogId == "ev-no-duration")
        } finally {
            db.closeConnection()
        }
    }
}
