package app.sila.core.database

import androidx.room3.useReaderConnection
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.dao.TopFiveMaterializationUpdate
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات تجسيد «أهم 5 أشخاص» فوق الواجهة (T8-04 · §F من الخطة المُصادقة · Issue #29):
 * - **الذرّية (§F.8/§F.11-د):** خطاف الفشل بعد كتابة الدفعة وقبل `COMMIT` يُثبت التراجع
 *   الكامل — لا صفوف جزئية في `contacts.category` ولا في صفوف `arham` ولا في الكاش.
 * - **حفظ ملكية المستخدم (§F.5):** صف `arham` قائم (بما فيه `lastContactedAt` — AC-8)
 *   يبقى حرفياً، ويُوحَّد التصنيف حصراً.
 * - **لا صفوف وهمية (§F.7):** الهدف غير الحيّ (غائب/مقبر) يُتخطى ولا يُنشأ له صف.
 * - **حقيقة ما بعد COMMIT:** الكاش يطابق SQL الخام حرفياً بعد كل نجاح (SSOT).
 * - **سلامة الأعمدة المجاورة (§F.9):** الهواتف و`createdAt`/`updatedAt` وفهرس FTS
 *   لا تُلمس إطلاقاً (سبق `updateCategories`).
 */
class ArhamMaterializationTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val createdAt = 1_700_000_000_000L
    private val updatedAt = 1_750_000_000_000L

    /** جهة حيّة بلا معلومة أرحام وبتصنيف غير عائلي — الحالة الطبيعية قبل التجسيد. */
    private fun plainContact(
        displayName: String,
        systemContactId: String,
        category: Category = Category.WORK,
        arham: ArhamInfo? = null,
    ) = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
        category = category,
        arham = arham,
        normalizedName = displayName,
    )

    private suspend fun LocalSilaDatabase.seed(
        contact: Contact,
    ): Long {
        upsertContact(contact.copy(createdAt = createdAt, updatedAt = updatedAt))
        return assertNotNull(
            observeContacts().first().firstOrNull { it.systemContactId == contact.systemContactId },
            "البذر: جهة التسجيل دخلت الكاش بعد COMMIT",
        ).id
    }

    /** حقيقة SQL الخام — لا ثقة بالكاش في التحقق (نمط اختبارات T8-03). */
    private suspend fun LocalSilaDatabase.rawCategory(contactId: Long): String? {
        var value: String? = null
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT category FROM contacts WHERE id = ?") { stmt ->
                stmt.bindLong(1, contactId)
                if (stmt.step()) value = stmt.getText(0)
            }
        }
        return value
    }

    private suspend fun LocalSilaDatabase.rawArhamRow(contactId: Long): Triple<String, String, Long?>? {
        var row: Triple<String, String, Long?>? = null
        room.useReaderConnection { conn ->
            conn.usePrepared(
                "SELECT relation, frequency, lastContactedAt FROM arham WHERE contactId = ?"
            ) { stmt ->
                stmt.bindLong(1, contactId)
                if (stmt.step()) {
                    row = Triple(
                        stmt.getText(0),
                        stmt.getText(1),
                        if (stmt.isNull(2)) null else stmt.getLong(2),
                    )
                }
            }
        }
        return row
    }

    private suspend fun LocalSilaDatabase.rawArhamRowCount(): Int {
        var count = -1
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM arham") { stmt ->
                stmt.step()
                count = stmt.getLong(0).toInt()
            }
        }
        return count
    }

    private suspend fun LocalSilaDatabase.rawPhonesCount(contactId: Long): Int {
        var count = -1
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM contact_phones WHERE contactId = ?") { stmt ->
                stmt.bindLong(1, contactId)
                stmt.step()
                count = stmt.getLong(0).toInt()
            }
        }
        return count
    }

    private suspend fun LocalSilaDatabase.rawTimestamps(contactId: Long): Pair<Long, Long> {
        var pair = 0L to 0L
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT createdAt, updatedAt FROM contacts WHERE id = ?") { stmt ->
                stmt.bindLong(1, contactId)
                if (stmt.step()) pair = stmt.getLong(0) to stmt.getLong(1)
            }
        }
        return pair
    }

    private suspend fun LocalSilaDatabase.rawFtsDocId(contactId: Long): Long? {
        var docid: Long? = null
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT docid FROM contacts_fts WHERE docid = ?") { stmt ->
                stmt.bindLong(1, contactId)
                if (stmt.step()) docid = stmt.getLong(0)
            }
        }
        return docid
    }

    @Test
    fun applyTopFiveMaterialization_freshMatch_categoryUnifiedAndArhamFilled() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val firstId = db.seed(plainContact("الأم فاطمة", "sys_mother"))
            val secondId = db.seed(plainContact("العم خالد", "sys_uncle"))
            assertFalse(db.observeArhamContacts().first().any { it.id in listOf(firstId, secondId) })

            val result = db.applyTopFiveMaterialization(
                updates = listOf(
                    TopFiveMaterializationUpdate(
                        firstId,
                        ArhamInfo(relation = Relation.GRANDMOTHER, frequency = ArhamFrequency.WEEKLY),
                    ),
                    TopFiveMaterializationUpdate(
                        secondId,
                        ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.MONTHLY),
                    ),
                ),
            )

            assertEquals(2, result.materialized, "§F.4: الاثنان مُوِّدا (صف arham غائب فمُلئ)")
            assertEquals(0, result.preservedUserOwned)
            assertEquals(0, result.skippedMissing)
            assertEquals("FAMILY", db.rawCategory(firstId), "التصنيف وُحِّد إلى FAMILY")
            assertEquals("FAMILY", db.rawCategory(secondId))
            assertEquals(
                Triple("GRANDMOTHER", "WEEKLY", null),
                db.rawArhamRow(firstId),
                "صف arham مُلئ من الاختيار (بلا lastContactedAt — لا يُختلق AC-8)",
            )
            // الكاش بعد COMMIT يطابق القرص (SSOT):
            val cachedFirst = db.getContactById(firstId)
            assertEquals(Category.FAMILY, cachedFirst?.category)
            assertEquals(Relation.GRANDMOTHER, cachedFirst?.arham?.relation)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applyTopFiveMaterialization_existingArhamWins_lastContactedUntouched() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val lastContacted = 1_790_000_000_000L
            val id = db.seed(
                plainContact(
                    "الجد عبده",
                    "sys_grandpa",
                    arham = ArhamInfo(
                        relation = Relation.UNCLE_PATERNAL,
                        frequency = ArhamFrequency.MONTHLY,
                        lastContactedAt = lastContacted,
                    ),
                ),
            )

            val result = db.applyTopFiveMaterialization(
                updates = listOf(
                    TopFiveMaterializationUpdate(
                        id,
                        ArhamInfo(relation = Relation.GRANDFATHER, frequency = ArhamFrequency.WEEKLY),
                    ),
                ),
            )

            assertEquals(0, result.materialized, "§F.5: لا استبدال لمعلومة المستخدم القائمة")
            assertEquals(1, result.preservedUserOwned, "التصنيف وُحِّد حصراً")
            assertEquals(
                Triple("UNCLE_PATERNAL", "MONTHLY", lastContacted),
                db.rawArhamRow(id),
                "صف arham القائم حرفي — بما فيه lastContactedAt (AC-8 لا يُلمس)",
            )
            assertEquals("FAMILY", db.rawCategory(id), "وحده التصنيف وُحِّد")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applyTopFiveMaterialization_faultInBatch_fullRollback_noPartialRowsOrCacheDrift() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val firstId = db.seed(plainContact("الأخت سعاد", "sys_sis1"))
            val secondId = db.seed(plainContact("الخالة نجاة", "sys_aunt"))
            val updates = listOf(
                TopFiveMaterializationUpdate(
                    firstId,
                    ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                ),
                TopFiveMaterializationUpdate(
                    secondId,
                    ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                ),
            )

            db.materializationFaultHook = { IllegalStateException("حقن فشل قبل COMMIT (اختبار ذرّية §F.8)") }
            try {
                assertFailsWith<IllegalStateException> {
                    db.applyTopFiveMaterialization(updates)
                }
            } finally {
                db.materializationFaultHook = null
            }

            // لا صفوف جزئية على القرص:
            assertEquals(0, db.rawArhamRowCount(), "التراجع الكامل: لا صف arham جزئي")
            assertEquals("WORK", db.rawCategory(firstId), "ولا تصنيف مُوحَّد جزئي")
            assertEquals("WORK", db.rawCategory(secondId))
            // الكاش لم ينحرف عن القرص (تحديث الكاش بعد COMMIT حصراً):
            assertEquals(Category.WORK, db.getContactById(firstId)?.category, "الكاش بلا انحراف بعد الفشل")
            assertNull(db.getContactById(firstId)?.arham)

            // §F.6: إعادة المحاولة بعد زوال العطل تكمل idempotently.
            val retry = db.applyTopFiveMaterialization(updates)
            assertEquals(2, retry.materialized)
            assertEquals("FAMILY", db.rawCategory(firstId))
            assertEquals(2, db.rawArhamRowCount())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applyTopFiveMaterialization_missingAndTombstonedTargets_skippedNotFabricated() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val liveId = db.seed(plainContact("العم سمير", "sys_live"))
            val tombstonedId = db.seed(plainContact("المقبرة زينب", "sys_tomb"))
            db.tombstoneContact(tombstonedId, nowMillis = updatedAt + 1_000)

            val result = db.applyTopFiveMaterialization(
                updates = listOf(
                    TopFiveMaterializationUpdate(
                        liveId,
                        ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                    ),
                    TopFiveMaterializationUpdate(
                        tombstonedId,
                        ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                    ),
                    TopFiveMaterializationUpdate(
                        987_654_321L,
                        ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                    ),
                ),
            )

            assertEquals(1, result.materialized, "الحيّ وحده مُوِّد")
            assertEquals(2, result.skippedMissing, "§F.7: المقبر والغائب يُتخطيان بلا اختلاق")
            assertEquals("FAMILY", db.rawCategory(liveId))
            assertEquals("WORK", db.rawCategory(tombstonedId), "المقبرة لم تُلمس")
            assertTrue(db.rawArhamRow(tombstonedId) == null, "ولا صف arham وهمي لها")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applyTopFiveMaterialization_duplicateContactIds_singleArhamRow() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.seed(plainContact("الابن وليد", "sys_son"))
            val duplicateArham = ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY)

            val result = db.applyTopFiveMaterialization(
                updates = listOf(
                    TopFiveMaterializationUpdate(id, duplicateArham),
                    TopFiveMaterializationUpdate(id, duplicateArham),
                ),
            )

            assertEquals(1, result.materialized, "التكرار ينهار إلى تطبيق واحد (idempotent §F.6)")
            assertEquals(1, db.rawArhamRowCount(), "صف arham واحد حصراً")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun applyTopFiveMaterialization_phonesTimestampsFts_untouched() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val id = db.seed(plainContact("العم عادل", "sys_phones"))
            val phonesBefore = db.rawPhonesCount(id)
            val timestampsBefore = db.rawTimestamps(id)
            val ftsBefore = db.rawFtsDocId(id)

            db.applyTopFiveMaterialization(
                updates = listOf(
                    TopFiveMaterializationUpdate(
                        id,
                        ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
                    ),
                ),
            )

            assertEquals(phonesBefore, db.rawPhonesCount(id), "§F.9: الهواتف لا تُلمس")
            assertEquals(timestampsBefore, db.rawTimestamps(id), "§F.9: createdAt/updatedAt لا يُلمان (سبق updateCategories)")
            assertEquals(ftsBefore, db.rawFtsDocId(id), "§F.9: فهرس FTS يبقى كما هو")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun findContactsBySystemContactIds_liveMatchesOnly_sortedById() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val firstId = db.seed(plainContact("أ", "sys_a"))
            val secondId = db.seed(plainContact("ب", "sys_b"))
            db.seed(plainContact("ج", "sys_c"))
            db.tombstoneContact(secondId, nowMillis = updatedAt + 5_000)

            val found = db.findContactsBySystemContactIds(setOf("sys_a", "sys_b", "sys_unknown"))

            assertEquals(listOf(firstId), found.map { it.id }, "§F.2 المرآة الحيّة: المقبر غير مطابق؛ الغائب كذلك")
            assertEquals("sys_a", found.single().systemContactId)
            assertTrue(db.findContactsBySystemContactIds(emptySet()).isEmpty(), "المجموعة الفارغة بلا قراءات")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun hasLiveContacts_reflectsLiveMirror() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            assertFalse(db.hasLiveContacts(), "قبل البذر: دفتر فارغ (حارس الجاهزية §F.2)")
            db.seed(plainContact("العم ياسر", "sys_one"))
            assertTrue(db.hasLiveContacts(), "بعد البذر: جهة حيّة واحدة تكفي")
        } finally {
            db.closeConnection()
        }
    }
}
