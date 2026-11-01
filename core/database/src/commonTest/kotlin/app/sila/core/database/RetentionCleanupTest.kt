package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.DisputeStatus
import app.sila.core.model.MessageDirection
import app.sila.core.model.MessageStatus
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات انحدار F-02 لمسار `cleanupExpiredData`:
 * - حدود الاستبقاء بالضبط (الحذف `<` حصراً — السجل عند الحد يبقى).
 * - معاملة واحدة: فشل منتصف التنظيف يُرجِع كل شيء، والإعادة عند الإقلاع التالي تُكمل.
 * - التكرار idempotent (تقرير صفري بلا أثر).
 * - سجلات المستخدم غير المشمولة بالسياسة لا تُمسّ أبداً.
 *
 * نطاقات السياسة الثابتة (لا تُغيَّر): call_events 365 يوماً، arham_inbox 30 يوماً،
 * أرشفة المحلوم من dispute_logs 90 يوماً، حذف قبور جهات أقدم من 30 يوماً (ADR-0010 §6).
 */
class RetentionCleanupTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val dayMs = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun syncContact(systemContactId: String, displayName: String) = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
        normalizedName = displayName
    )

    private fun activeArhamContact(systemContactId: String) = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = "خالة حية قديمة التواصل"),
        phones = listOf(Phone(raw = "01111111111", e164 = "+201111111111", isPrimary = true)),
        category = Category.FAMILY,
        arham = ArhamInfo(
            relation = Relation.AUNT_MATERNAL,
            frequency = ArhamFrequency.MONTHLY,
            lastContactedAt = now - (400 * dayMs) // قديمة جداً لكنها ليست قبراً — سياسة الاستبقاء لا تشملها
        ),
        normalizedName = "خالة حية قديمة التواصل"
    )

    @Test
    fun cleanupExpiredData_exactRetentionBoundary_isRetained_andOnlyStrictlyOlderIsDeleted() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // call_events: حدّ 365 يوماً — عند الحد يبقى، وأقدم بمللي ثانية واحدة يُحذف.
            db.seedCallEventsForTest(
                listOf(
                    CallEventEntity("call_exact", "+201011112222", CallDirection.OUT, 30, now - 365 * dayMs, CallSource.CALL_LOG),
                    CallEventEntity("call_past", "+201011112222", CallDirection.OUT, 30, now - 365 * dayMs - 1, CallSource.CALL_LOG)
                )
            )
            // arham_inbox: حدّ 30 يوماً — نفس الدقة.
            db.seedInboxForTest(
                listOf(
                    ArhamInboxEntity(id = 1L, senderUid = "u1", senderName = "مُرسل", recipientUid = "أنا", messageText = "عند الحد", direction = MessageDirection.RECEIVED, status = MessageStatus.READ, sentAt = now - 30 * dayMs),
                    ArhamInboxEntity(id = 2L, senderUid = "u2", senderName = "مُرسل", recipientUid = "أنا", messageText = "بعد الحد", direction = MessageDirection.RECEIVED, status = MessageStatus.READ, sentAt = now - 30 * dayMs - 1)
                )
            )
            // dispute_logs: حدّ أرشفة المحلوم 90 يوماً.
            db.seedDisputesForTest(
                listOf(
                    DisputeLogEntity(id = 1L, serviceCloudId = "srv_1", description = "عند الحد", status = DisputeStatus.RESOLVED, createdAt = now - 200 * dayMs, updatedAt = now - 90 * dayMs, resolvedAt = now - 90 * dayMs),
                    DisputeLogEntity(id = 2L, serviceCloudId = "srv_1", description = "بعد الحد", status = DisputeStatus.RESOLVED, createdAt = now - 200 * dayMs, updatedAt = now - 90 * dayMs - 1, resolvedAt = now - 90 * dayMs - 1)
                )
            )
            // القبور: حدّ 30 يوماً — قبر عند الحد يبقى قابلاً للاسترجاع، وأقدم بمللي ثانية يُحذف نهائياً.
            db.applySyncBatch(
                upserts = listOf(syncContact("tomb_exact", "قبر عند الحد"), syncContact("tomb_past", "قبر بعد الحد")),
                tombstones = emptyList(), snapshotJson = null, reconcileStampMillis = null, nowMillis = now - 60 * dayMs
            )
            db.applySyncBatch(emptyList(), listOf("tomb_exact"), null, null, now - 30 * dayMs)
            db.applySyncBatch(emptyList(), listOf("tomb_past"), null, null, now - 30 * dayMs - 1)

            val report = db.cleanupExpiredData(now)

            assertEquals(1, report.deletedCallEvents, "حدث المكالمة الأقدم من 365 يوماً بمللي ثانية يُحذف، وعند الحد يبقى")
            assertEquals(1, report.deletedInboxMessages, "رسالة الأرشيف الأقدم من 30 يوماً بمللي ثانية تُحذف، وعند الحد تبقى")
            assertEquals(1, report.archivedDisputes, "النزاع المحلوم الأقدم من 90 يوماً بمللي ثانية يُؤرشف، وعند الحد يبقى")
            assertEquals(1, report.deletedTombstonedContacts, "القبر الأقدم من 30 يوماً بمللي ثانية يُحذف نهائياً، وعند الحد يبقى")

            val rows = db.getSyncIndexRows()
            assertNotNull(rows.firstOrNull { it.systemContactId == "tomb_exact" && it.systemDeletedAt != null }, "القبر عند الحد بالضبط يبقى قابلاً للاسترجاع")
            assertNull(rows.firstOrNull { it.systemContactId == "tomb_past" }, "القبر الأقدم بمللي ثانية يُحذف نهائياً")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_rollsBackEntireTransaction_onMidCleanupFailure_andResumesOnRerun() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.seedCallEventsForTest(
                listOf(CallEventEntity("old_call", "+201011112222", CallDirection.OUT, 45, now - 400 * dayMs, CallSource.CALL_LOG))
            )
            db.seedInboxForTest(
                listOf(ArhamInboxEntity(id = 1L, senderUid = "u1", senderName = "مُرسل", recipientUid = "أنا", messageText = "قديمة", direction = MessageDirection.RECEIVED, status = MessageStatus.READ, sentAt = now - 40 * dayMs))
            )
            db.seedDisputesForTest(
                listOf(DisputeLogEntity(id = 1L, serviceCloudId = "srv_1", description = "محلوم قديم", status = DisputeStatus.RESOLVED, createdAt = now - 200 * dayMs, updatedAt = now - 100 * dayMs, resolvedAt = now - 100 * dayMs))
            )
            db.applySyncBatch(
                upserts = listOf(syncContact("tomb_expired", "قبر منتهٍ"), syncContact("tomb_fresh", "قبر حديث")),
                tombstones = emptyList(), snapshotJson = null, reconcileStampMillis = null, nowMillis = now - 60 * dayMs
            )
            db.applySyncBatch(emptyList(), listOf("tomb_expired"), null, null, now - 40 * dayMs)
            db.applySyncBatch(emptyList(), listOf("tomb_fresh"), null, null, now - 2 * dayMs)

            // انقطاع منتصف التنظيف (محاكاة انهيار العملية قبل COMMIT):
            db.cleanupFaultHook = { IllegalStateException("فشل مُحاقَن في منتصف المعاملة") }
            assertFailsWith<IllegalStateException> { db.cleanupExpiredData(now) }

            // لا شيء حُذف ولا أُرشف — المعاملة تُرجِعت كاملة:
            val rowsAfterFailure = db.getSyncIndexRows()
            assertNotNull(rowsAfterFailure.firstOrNull { it.systemContactId == "tomb_expired" && it.systemDeletedAt != null }, "القبر المنتهي بقى موجوداً بعد التراجع")
            assertNotNull(rowsAfterFailure.firstOrNull { it.systemContactId == "tomb_fresh" }, "القبر الحديث لم يُمسّ")

            db.cleanupFaultHook = null

            // الإقلاع التالي: إعادة التنظيف تُكمل كل العمل — إثبات أن الفشل لم يُرسِّم حذفاً جزئياً.
            val resumedReport = db.cleanupExpiredData(now)
            assertEquals(1, resumedReport.deletedCallEvents)
            assertEquals(1, resumedReport.deletedInboxMessages)
            assertEquals(1, resumedReport.archivedDisputes)
            assertEquals(1, resumedReport.deletedTombstonedContacts)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_isIdempotent_whenRunRepeatedly() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.seedCallEventsForTest(
                listOf(CallEventEntity("old_call", "+201011112222", CallDirection.OUT, 45, now - 400 * dayMs, CallSource.CALL_LOG))
            )
            db.applySyncBatch(
                upserts = listOf(syncContact("tomb_old", "قبر منتهٍ")),
                tombstones = emptyList(), snapshotJson = null, reconcileStampMillis = null, nowMillis = now - 60 * dayMs
            )
            db.applySyncBatch(emptyList(), listOf("tomb_old"), null, null, now - 40 * dayMs)

            val first = db.cleanupExpiredData(now)
            assertEquals(1, first.deletedCallEvents)
            assertEquals(1, first.deletedTombstonedContacts)

            // الساعة نفسها، التنظيف مرة أخرى: لا شيء مؤهل — تقرير صفري بلا أي حذف إضافي.
            val second = db.cleanupExpiredData(now)
            assertEquals(0, second.deletedCallEvents)
            assertEquals(0, second.deletedInboxMessages)
            assertEquals(0, second.archivedDisputes)
            assertEquals(0, second.deletedTombstonedContacts)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_preservesUserRecords_notCoveredByRetentionPolicy() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // جهة حية (غير مُقبرة) بتواصل قديم جداً + حدث مكالمة حديث + قبر حديث قابل للاسترجاع.
            db.upsertContact(activeArhamContact("user_active"))
            db.seedCallEventsForTest(
                listOf(CallEventEntity("recent_call", "+201011112222", CallDirection.OUT, 60, now - 10 * dayMs, CallSource.CALL_LOG))
            )
            db.applySyncBatch(
                upserts = listOf(syncContact("tomb_fresh", "قبر حديث")),
                tombstones = emptyList(), snapshotJson = null, reconcileStampMillis = null, nowMillis = now - 10 * dayMs
            )
            db.applySyncBatch(emptyList(), listOf("tomb_fresh"), null, null, now - 2 * dayMs)

            val report = db.cleanupExpiredData(now)

            assertEquals(0, report.deletedCallEvents, "المكالمات الحديثة لا تُمسّ")
            assertEquals(0, report.deletedTombstonedContacts, "القبور داخل نافذة الاسترجاع (30 يوماً) لا تُحذف")

            val contacts = db.observeContacts().first()
            assertNull(contacts.firstOrNull { it.systemContactId == "tomb_fresh" }, "القبر الحديث مخفي من القائمة (سلوك T2)")
            val rows = db.getSyncIndexRows()
            assertNotNull(rows.firstOrNull { it.systemContactId == "tomb_fresh" && it.systemDeletedAt != null }, "القبر الحديث يبقى صفوفاً قابلة للاسترجاع")
            assertNotNull(rows.firstOrNull { it.systemContactId == "user_active" && it.systemDeletedAt == null }, "جهة المستخدم الحية غير مشمولة بالاستبقاء إطلاقاً")
            assertTrue(db.observeContacts().first().any { it.systemContactId == "user_active" }, "جهة المستخدم الحية باقية في القائمة")

            // تنظيف متكرر: لا حذف إضافي إطلاقاً.
            val repeat = db.cleanupExpiredData(now)
            assertEquals(0, repeat.deletedCallEvents)
            assertEquals(0, repeat.deletedTombstonedContacts)
        } finally {
            db.closeConnection()
        }
    }
}
