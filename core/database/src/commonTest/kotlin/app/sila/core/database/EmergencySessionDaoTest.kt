package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.model.RingingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات DAO جلسات رنين الطوارئ (T8-10 · D4 v3 · §N · Issue #29) فوق Room `:memory:`:
 * - حد المعدل: عدّ **كل** الحالات ضمن النافذة (`startedAt > cutoff` صارم — بدأتَ عند
 *   الحد تماماً لا تُحتسب) — §N.2/§C.1-8.
 * - المحاولات: ذرّية `attempts+1` مع إرجاع العدد الجديد، و`null` بعد الحسم (§N.8).
 * - الحسم: idempotent — الأول `true` يكتب، الثاني `false` بلا مساس.
 * - المراقبة: نبضة Room تعيد الجارية/فارغة حسب الحالة (SSOT — D3).
 */
class EmergencySessionDaoTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val now = 1_800_000_000_000L

    @Test
    fun insertInProgress_assignsId_rowIsReadableAndObservable() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.insertInProgressSession(contactId = 7L, startedAtMillis = now)

            assertTrue(id > 0L, "المعرف التوليدي داخل المعاملة موجب")
            val inProgress = assertNotNull(db.findInProgressSession(), "الجارية الوحيدة تُقرأ")
            assertEquals(id, inProgress.id)
            assertEquals(7L, inProgress.contactId)
            assertEquals(0, inProgress.attempts)
            assertEquals(RingingStatus.IN_PROGRESS, inProgress.status)
            assertEquals(now, inProgress.startedAt)
            assertEquals(now, inProgress.lastAttemptAt)

            assertEquals(inProgress.id, db.observeInProgressSession().first()?.id, "المراقبة تنبض بالجارية")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun countSessionsStartedSince_countsAllStatuses_withinWindow_only() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val cutoff = now - 24L * 60 * 60 * 1000 // نافذة 24 ساعة (D4)
            val expired = cutoff - 1L

            // 3 ضمن النافذة بحالات مختلفة — **كلها تُحتسب** (§C.1-8: بلا تمييز حالة).
            // get-or-create ذرّي (مراجعة T8-10 · P1-2): الإدراج الجديد يشترط لا جارية
            // سليفة — تُحسم السابقة قبل إنشاء التالية (سلوك الالتحاق المقصود).
            val first = db.insertInProgressSession(contactId = 1L, startedAtMillis = now - 1000)
            db.finishSession(first, RingingStatus.ANSWERED, atMillis = now)
            val second = db.insertInProgressSession(contactId = 1L, startedAtMillis = now - 500)
            db.finishSession(second, RingingStatus.STOPPED_BY_USER, atMillis = now)
            val third = db.insertInProgressSession(contactId = 2L, startedAtMillis = now - 100)
            db.finishSession(third, RingingStatus.MAX_REACHED, atMillis = now)
            // خارج النافذة (قبل الكَت الأكبر + منتهية) — لا تُحتسب.
            val old = db.insertInProgressSession(contactId = 3L, startedAtMillis = expired)
            db.finishSession(old, RingingStatus.MAX_REACHED, atMillis = expired)

            assertEquals(3, db.countSessionsStartedSince(cutoff), "كل الحالات ضمن النافذة تُحتسب")
            // القطع الصارم `>`: من بدأت عند الكَت تماماً خارج العدّ (سياسة القصوى) —
            // عند الكَت: now-500؛ تُحتسب فقط startedAt > now-500 أي (now-100) حصراً.
            assertEquals(1, db.countSessionsStartedSince(cutoffMillis = now - 500))
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun insertInProgress_isAtomicGetOrCreate_noSecondInProgressRow() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val first = db.insertInProgressSession(contactId = 7L, startedAtMillis = now)

            // سباق «انطلقين متوازيين» (مراجعة T8-10 · P1-2): الإدراج الثاني بوجود جارية
            // قائمة يلتحق بها — نفس المعرف، بلا صفٍ ثانٍ يُفسد محاسبة 3/24 ساعة (§N.8).
            val second = db.insertInProgressSession(contactId = 8L, startedAtMillis = now + 1)
            assertEquals(first, second, "الإدراج الثانٍ يلتحق بالجارية القائمة (نفس المعرف)")
            val inProgress = assertNotNull(db.findInProgressSession(), "الجارية الوحيدة تُقرأ")
            assertEquals(first, inProgress.id)
            assertEquals(7L, inProgress.contactId, "هوية الصف الأصلي دون مساس")
            assertEquals(1, db.countSessionsStartedSince(cutoffMillis = now - 1), "صفٌ واحد حصراً في النافذة")

            // بعد الحسم: الالتحاق يشترط «جارية قائمة» حصراً — الإدراج يُنشئ صفّاً جديداً.
            db.finishSession(first, RingingStatus.STOPPED_BY_USER, atMillis = now + 2)
            val third = db.insertInProgressSession(contactId = 8L, startedAtMillis = now + 3)
            assertTrue(third != first, "بعد الحسم يُنشأ صف جديد (لا التصاق بجارية منتهية)")
            assertEquals(third, assertNotNull(db.findInProgressSession()).id)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun recordAttempt_incrementsAtomically_andReturnsNewCount() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.insertInProgressSession(contactId = 7L, startedAtMillis = now)

            for (expected in 1..10) {
                val attempts = assertNotNull(
                    db.recordAttempt(id, atMillis = now + expected),
                    "المحاولة $expected تعيد العدد الجديد",
                )
                assertEquals(expected, attempts)
            }
            assertEquals(10, db.findInProgressSession()?.attempts)
            assertEquals(now + 10, db.findInProgressSession()?.lastAttemptAt, "وقت آخر محاولة رُفع")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun recordAttempt_returnsNull_whenSessionFinishedOrMissing() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.insertInProgressSession(contactId = 7L, startedAtMillis = now)

            assertNull(db.recordAttempt(sessionId = 999L, atMillis = now), "مفقود ⇒ null (idempotent)")
            db.finishSession(id, RingingStatus.ANSWERED, atMillis = now)
            assertNull(db.recordAttempt(id, atMillis = now), "محسومة ⇒ null (§N.8)")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun finishSession_isIdempotent_firstTrueThenFalse() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.insertInProgressSession(contactId = 7L, startedAtMillis = now)

            assertTrue(db.finishSession(id, RingingStatus.STOPPED_BY_USER, atMillis = now), "الحسم الأول يكتب")
            assertFalse(db.finishSession(id, RingingStatus.ANSWERED, atMillis = now), "الثاني عملية ساكنة (§N.8)")

            val entity = assertNotNull(db.getEmergencySessionForTest(id))
            assertEquals(RingingStatus.STOPPED_BY_USER, entity.status, "الحالة الأولى باقية — بلا إعادة كتابة")
            assertNull(db.findInProgressSession(), "لم تعد جارية")
            assertEquals(null, db.observeInProgressSession().first(), "المراقبة تنبض بالفراغ بعد الحسم")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun finishSession_returnsFalse_whenMissing() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            assertFalse(db.finishSession(sessionId = 42L, status = RingingStatus.INTERRUPTED, atMillis = now))
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun observeInProgress_emitsNull_whenNoSession() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            assertEquals(null, db.observeInProgressSession().first())
            assertEquals(null, db.findInProgressSession())
            assertEquals(0, db.countSessionsStartedSince(cutoffMillis = 0L))
        } finally {
            db.closeConnection()
        }
    }
}

/** قراءة مباشرة بمعرف — فحص الحسم (خارج عقد الـ DAO، للاختبار حصراً). */
private suspend fun LocalSilaDatabase.getEmergencySessionForTest(id: Long): EmergencyRingingSessionEntity? {
    val all = readEmergencySessionsForTest()
    return all.firstOrNull { it.id == id }
}
