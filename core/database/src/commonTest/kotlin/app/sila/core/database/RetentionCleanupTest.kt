package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.EmergencyRingingSessionEntity
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
import app.sila.core.model.RingingStatus
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
 * أرشفة المحلوم من dispute_logs 90 يوماً، حذف قبور جهات أقدم من 30 يوماً (ADR-0010 §6)،
 * تطهير جلسات الطوارئ المنتهية (أي حالة غير IN_PROGRESS) بعد 48 ساعة من آخر محاولة (قرار D4 · T8-02).
 */
class RetentionCleanupTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L
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
                    legacyCallEvent("call_exact", "+201011112222", CallDirection.OUT, 30, now - 365 * dayMs, CallSource.CALL_LOG),
                    legacyCallEvent("call_past", "+201011112222", CallDirection.OUT, 30, now - 365 * dayMs - 1, CallSource.CALL_LOG)
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
                listOf(legacyCallEvent("old_call", "+201011112222", CallDirection.OUT, 45, now - 400 * dayMs, CallSource.CALL_LOG))
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
                listOf(legacyCallEvent("old_call", "+201011112222", CallDirection.OUT, 45, now - 400 * dayMs, CallSource.CALL_LOG))
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
                listOf(legacyCallEvent("recent_call", "+201011112222", CallDirection.OUT, 60, now - 10 * dayMs, CallSource.CALL_LOG))
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

    // ===== جلسات الطوارئ (D4 — T8-02): تطهير المنتهية بعد 48 ساعة من آخر محاولة =====

    /** مُعين جلسة طوارئ للاختبار — `startedAt = lastAttemptAt` (كافٍ لسياسة التطهير المبنية على آخر محاولة). */
    private fun emergencySession(
        id: Long,
        contactId: Long,
        lastAttemptAt: Long,
        status: RingingStatus
    ) = EmergencyRingingSessionEntity(
        id = id,
        contactId = contactId,
        startedAt = lastAttemptAt,
        lastAttemptAt = lastAttemptAt,
        attempts = 1,
        status = status
    )

    @Test
    fun cleanupExpiredData_oldTerminalEmergencySessions_areDeleted_andReported() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // الحالات المنتهية الأربع كلها (المنتهي = أي RingingStatus غير IN_PROGRESS)
            // — جميعها أقدم من 48 ساعة بمللي ثانية واحدة.
            db.seedEmergencySessionsForTest(
                listOf(
                    emergencySession(1L, contactId = 101L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.ANSWERED),
                    emergencySession(2L, contactId = 102L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.STOPPED_BY_USER),
                    emergencySession(3L, contactId = 103L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.MAX_REACHED),
                    emergencySession(4L, contactId = 104L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.BLOCKED)
                )
            )

            val report = db.cleanupExpiredData(now)

            assertEquals(4, report.deletedEmergencySessions, "الجلسات المنتهية الأربع الأقدم من 48 ساعة تُحذف جميعاً ويُبلَّغ عنها")
            assertTrue(db.readEmergencySessionsForTest().isEmpty(), "لا صفوف متبقية في الجدول بعد التطهير")
            // بلا بيانات للسياسات الأخرى — تقاريرها صفرية (سلوك غير متأثر).
            assertEquals(0, report.deletedCallEvents)
            assertEquals(0, report.deletedInboxMessages)
            assertEquals(0, report.archivedDisputes)
            assertEquals(0, report.deletedTombstonedContacts)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_emergencySession48HourBoundary_exactSemantics() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // اتفاقية الحد الموثقة (KDoc هذا الملف + SQL الفعلي `lastAttemptAt < ?`):
            // الحذف `<` حصراً — عند الحد بالضبط تبقى، والأقدم بمللي ثانية تُحذف، والأحدث تبقى.
            db.seedEmergencySessionsForTest(
                listOf(
                    emergencySession(1L, contactId = 201L, lastAttemptAt = now - 48 * hourMs, status = RingingStatus.ANSWERED),
                    emergencySession(2L, contactId = 202L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.ANSWERED),
                    emergencySession(3L, contactId = 203L, lastAttemptAt = now - 48 * hourMs + 1, status = RingingStatus.ANSWERED)
                )
            )

            val report = db.cleanupExpiredData(now)

            assertEquals(1, report.deletedEmergencySessions, "يُحذف ما هو أقدم من 48 ساعة حصراً")
            assertEquals(listOf(1L, 3L), db.readEmergencySessionsForTest().map { it.id }, "عند الحد بالضبط وأحدث منه يبقيان")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_activeEmergencySession_survivesRegardlessOfAge() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // الحالة النشطة الوحيدة (IN_PROGRESS) — لا تُمس مهما بلغ عمرها:
            // قرارها بيد منطق الاستئناف/الإجهاض حصراً (قرار D4).
            val active = emergencySession(1L, contactId = 301L, lastAttemptAt = now - 400 * dayMs, status = RingingStatus.IN_PROGRESS)
            db.seedEmergencySessionsForTest(listOf(active))

            val report = db.cleanupExpiredData(now)

            assertEquals(0, report.deletedEmergencySessions, "الجلسة النشطة القديمة جداً لا تُحذف ولا تُعدّ محذوفة")
            assertEquals(listOf(active), db.readEmergencySessionsForTest(), "صف الجلسة النشطة باقٍ بلا أي تغيير")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun cleanupExpiredData_mixedEmergencySessions_exactDeletionCount_andIdempotentRerun() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.seedEmergencySessionsForTest(
                listOf(
                    emergencySession(10L, contactId = 310L, lastAttemptAt = now - 48 * hourMs - 1, status = RingingStatus.ANSWERED),
                    emergencySession(20L, contactId = 320L, lastAttemptAt = now - 100 * dayMs, status = RingingStatus.BLOCKED),
                    emergencySession(30L, contactId = 330L, lastAttemptAt = now - 48 * hourMs, status = RingingStatus.STOPPED_BY_USER),
                    emergencySession(40L, contactId = 340L, lastAttemptAt = now - 10 * hourMs, status = RingingStatus.MAX_REACHED),
                    emergencySession(50L, contactId = 350L, lastAttemptAt = now - 400 * dayMs, status = RingingStatus.IN_PROGRESS)
                )
            )

            // التشغيل الأول: حذف المنتهيتين المؤهلتين حصراً بعدد مضبوط.
            val first = db.cleanupExpiredData(now)
            assertEquals(2, first.deletedEmergencySessions, "عدد محذوفات مضبوط: المنتهيتان الأقدم من 48 ساعة فقط")
            assertEquals(listOf(30L, 40L, 50L), db.readEmergencySessionsForTest().map { it.id }, "عند الحد + داخل النافذة + النشطة يبقون جميعاً")
            assertEquals(0, first.deletedCallEvents, "عمليات التنظيف الأخرى بلا بيانات — لا حذف مكالمات")
            assertEquals(0, first.deletedInboxMessages, "بلا حذف رسائل")
            assertEquals(0, first.archivedDisputes, "بلا أرشفة نزاعات")
            assertEquals(0, first.deletedTombstonedContacts, "بلا حذف قبور")

            // التشغيل الثاني عند الساعة نفسها: idempotent — لا حذف إضافي ولا عدّ مزدوج.
            val second = db.cleanupExpiredData(now)
            assertEquals(0, second.deletedEmergencySessions, "الإعادة لا تعدّ محذوفات سابقة")
            assertEquals(listOf(30L, 40L, 50L), db.readEmergencySessionsForTest().map { it.id }, "المحميات باقية بعد الإعادة")
        } finally {
            db.closeConnection()
        }
    }
}
