package app.sila.core.database

import androidx.room3.useReaderConnection
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.ArhamDataChangeListener
import app.sila.core.database.dao.ClassificationRestoreOutcome
import app.sila.core.database.dao.ClassificationRestorePlan
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.ImportantDate
import app.sila.core.model.Relation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * اختبارات ذرّية استعادة التصنيفات فوق التنفيذ الحقيقي (تذكرة #60 · S1-1 · S3-22 · ADR-0015):
 * عقد «كل-أو-لا-شيء» — انقطاع وسط المعاملة (أثقل الأشكال: محوّة بلا استعادة) يُرجِع
 * القاعدة كما-كانت حرفاً، والمسارات السعيدة تطبّق عهد الدلالة القديم (شطف يحفظ
 * التواريخ، أرحام يُلزم تصنيفاً FAMILY، غياب داخل المعاملة يُعلَم بلا كتابة).
 */
class ClassificationRestoreAtomicityTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private suspend fun seedContact(
        db: LocalSilaDatabase,
        id: Long,
        displayName: String,
        category: Category = Category.UNCATEGORIZED,
        tags: List<String> = emptyList(),
        arham: ArhamInfo? = null,
        dates: List<ImportantDate> = emptyList()
    ) {
        db.upsertContact(
            Contact(
                id = id,
                systemContactId = "sys_$id",
                lookupKey = "lookup_$id",
                name = ContactName(displayName = displayName),
                category = category,
                tags = tags,
                arham = arham,
                dates = dates
            )
        )
    }

    /** نسخة محو (عهد REPLACE القديم): تصنيف عطيف/وسوم فارغة/بلا أرحام — البقية محفوظة. */
    private fun cleared(contact: Contact) = contact.copy(
        category = Category.UNCATEGORIZED,
        tags = emptyList(),
        arham = null
    )

    // ——— حقيقة SQL الخام — لا ثقة بالكاش في إثبات التراجع (نمط ArhamMaterializationTest §T8-03) ———

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

    /** الوسوم صفوف `contact_custom_fields` بـ label='TAG' — عدّها حقيقية القرص. */
    private suspend fun LocalSilaDatabase.rawTagCount(contactId: Long): Int {
        var count = -1
        room.useReaderConnection { conn ->
            conn.usePrepared(
                "SELECT COUNT(*) FROM contact_custom_fields WHERE contactId = ? AND label = 'TAG'"
            ) { stmt ->
                stmt.bindLong(1, contactId)
                stmt.step()
                count = stmt.getLong(0).toInt()
            }
        }
        return count
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

    /** أول صف تاريخ لجهة — (type, month, day) للتحقق الحرفي من سلامة التواريخ. */
    private suspend fun LocalSilaDatabase.rawBirthdayRow(contactId: Long): Triple<String, Int, Int>? {
        var row: Triple<String, Int, Int>? = null
        room.useReaderConnection { conn ->
            conn.usePrepared("SELECT type, month, day FROM contact_dates WHERE contactId = ?") { stmt ->
                stmt.bindLong(1, contactId)
                if (stmt.step()) {
                    row = Triple(
                        stmt.getText(0),
                        stmt.getLong(1).toInt(),
                        stmt.getLong(2).toInt(),
                    )
                }
            }
        }
        return row
    }

    @Test
    fun replaceRestore_faultBetweenClearAndRewrites_leavesDatabaseExactlyAsBefore() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val arham = ArhamInfo(relation = Relation.BROTHER, frequency = ArhamFrequency.WEEKLY)
            val birthday = ImportantDate(type = "BIRTHDAY", month = 5, day = 20)
            seedContact(db, 1, "أخي الفاضل", category = Category.FAMILY, tags = listOf("طبيب"), arham = arham, dates = listOf(birthday))
            seedContact(db, 2, "زميل العمل", category = Category.WORK, tags = listOf("مكتب"))

            val beforeContacts = db.observeContacts().first()
            val beforeArham = db.observeArhamContacts().first()

            db.classificationRestoreFaultHook = { IllegalStateException("حقن فشل بعد الشطف وقبل الكتابات (اختبار ذرّية الاستعادة)") }
            val plan = ClassificationRestorePlan(
                replaceBeforeApply = true,
                replacementContacts = beforeContacts.map { cleared(it) },
                contactRewrites = beforeContacts.map { it.copy(category = Category.FAMILY) }
            )

            val thrown = assertFailsWith<IllegalStateException> {
                db.applyClassificationRestore(plan)
            }
            assertEquals("حقن فشل بعد الشطف وقبل الكتابات (اختبار ذرّية الاستعادة)", thrown.message)
            db.classificationRestoreFaultHook = null

            // عقد «كل-أو-لا-شيء» على طبقتين:
            // (1) المرآة: تحديثها بعد COMMIT حصراً — عند الفشل لم تُلمس أصلاً،
            //     فلا انحراف عن القرص (نمط «الكاش بلا انحراف بعد الفشل»).
            val afterContacts = db.observeContacts().first()
            assertEquals(beforeContacts, afterContacts, "الكاش بلا انحراف بعد الفشل (تحديثه بعد COMMIT حصراً)")
            assertEquals(beforeArham, db.observeArhamContacts().first())
            assertEquals(listOf(birthday), afterContacts.first { it.id == 1L }.dates)
            assertEquals(Category.WORK, afterContacts.first { it.id == 2L }.category)
            assertEquals(listOf("طبيب"), afterContacts.first { it.id == 1L }.tags)

            // (2) حقيقة القرص الخام — إثبات التراجع الفيزيائي على قاعدة الحقيقة لا على المرآة
            //     (نمط §F.8 · ArhamMaterializationTest): لو لم تتراجع المعاملة لعمّ الشطف
            //     التصنيفين إلى UNCATEGORIZED وفارِغا الوسوم وقبض صفا الأرحام.
            assertEquals("FAMILY", db.rawCategory(1L), "التراجع الكامل: تصنيف الجفة كما-كان على القرص")
            assertEquals("WORK", db.rawCategory(2L), "ولا تصنيف مُشطف جزئياً من REPLACE")
            assertEquals(1, db.rawTagCount(1L), "وسم الجفة بقِيَ على القرص — لا شطف جزئي")
            assertEquals(1, db.rawTagCount(2L), "وسم الزميل بقِيَ على القرص — لا شطف جزئي")
            assertEquals(1, db.rawArhamRowCount(), "لا صف arham جزئي على القرص")
            assertEquals(
                Triple("BIRTHDAY", 5, 20),
                db.rawBirthdayRow(1L),
                "تواريخ خارج نطاق الحمولة سليمة حرفاً (الشطف لا يمسها أصلاً)",
            )

            // §F.6: إعادة المحاولة بعد زوال العطل تكمل idempotently — الخطة نفسها تنطبق كلها
            // (الشطف يمحو ثم الإسناد يعيد الرحمة من نسخة الحمولة إلى الجفة حصراً).
            val retry = db.applyClassificationRestore(plan)
            assertEquals(2, retry.clearedContacts)
            assertEquals(2, retry.appliedContactIds.size)
            assertTrue(retry.missingContactIds.isEmpty())
            assertEquals("FAMILY", db.rawCategory(1L), "إعادة المحاولة: إسناد الجفة انطبق")
            assertEquals("FAMILY", db.rawCategory(2L), "إعادة المحاولة: إسناد الزميل انطبق")
            assertEquals(1, db.rawArhamRowCount(), "رحمة الجفة عادت بعد الشطف من نسخة الإسناد نفسها")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun replaceRestore_happyPath_clearsClassificationsAndAppliesUnifiedRewritesAtomically() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            val birthday2 = ImportantDate(type = "BIRTHDAY", month = 3, day = 14)
            seedContact(db, 1, "أخي الفاضل", category = Category.WORK, tags = listOf("قديم"))
            seedContact(db, 2, "زميل العمل", category = Category.WORK, tags = listOf("مكتب"), dates = listOf(birthday2))

            val all = db.observeContacts().first()
            val clearedById = all.associate { it.id to cleared(it) }
            val rewrite1 = clearedById.getValue(1L).copy(
                category = Category.FAMILY,
                tags = listOf("عائلة", "إخوة"),
                arham = ArhamInfo(relation = Relation.BROTHER, frequency = ArhamFrequency.WEEKLY)
            )

            val outcome = db.applyClassificationRestore(
                ClassificationRestorePlan(
                    replaceBeforeApply = true,
                    replacementContacts = clearedById.values.toList(),
                    contactRewrites = listOf(rewrite1)
                )
            )

            assertEquals(2, outcome.clearedContacts)
            assertEquals(listOf(1L), outcome.appliedContactIds)
            assertTrue(outcome.missingContactIds.isEmpty())

            // بعد COMMIT: الحمولة الموحّدة سائدة على أساس المحو، والشطف وصل من لم يمسّه الحمولة.
            val mirrored = db.observeContacts().first()
            val c1 = mirrored.first { it.id == 1L }
            assertEquals(Category.FAMILY, c1.category)
            assertEquals(listOf("عائلة", "إخوة"), c1.tags)
            assertEquals(Relation.BROTHER, c1.arham?.relation)
            assertTrue(c1.dates.isEmpty())
            val c2 = mirrored.first { it.id == 2L }
            assertEquals(Category.UNCATEGORIZED, c2.category)
            assertTrue(c2.tags.isEmpty())
            assertEquals(null, c2.arham)
            // الشطف لا يمس ما لا تملكه الحمولة (عهد المسار القديم): التواريخ محفوظة.
            assertEquals(listOf(birthday2), c2.dates)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun restore_missingOrTombstonedContactsInsideTransaction_areReportedAndNeverWritten() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            seedContact(db, 1, "أخي الفاضل", category = Category.WORK)
            seedContact(db, 3, "قبرٌ قادم", category = Category.FRIENDS, tags = listOf("جامعة"))

            // القبر قبل الخطة: نسخ الخطة تظاهر بالحيّ (كما في مسابقة الكاش والحياة)
            // ثم يُكتشف القبر داخل المعاملة فيُعلَم ويُمنع من الكتابة.
            val rewrite1 = db.observeContacts().first().first { it.id == 1L }.copy(category = Category.FAMILY)
            val rewrite3 = db.observeContacts().first().first { it.id == 3L }.copy(category = Category.FAMILY)
            db.tombstoneContact(3L, nowMillis = 1_800_000_000_000L)

            val outcome = db.applyClassificationRestore(
                ClassificationRestorePlan(
                    replaceBeforeApply = false,
                    replacementContacts = emptyList(),
                    contactRewrites = listOf(
                        rewrite1,
                        rewrite3.copy(id = 99L), // معرف غائب سلفاً
                        rewrite3
                    )
                )
            )

            assertEquals(0, outcome.clearedContacts)
            assertEquals(listOf(1L), outcome.appliedContactIds)
            assertEquals(listOf(99L, 3L), outcome.missingContactIds)

            // المطبَّق وحده تغيّر — لا كتابة لمعرفٍ محدوم ولا إحياء قبر.
            val after = db.observeContacts().first()
            assertEquals(Category.FAMILY, after.first { it.id == 1L }.category)
            assertEquals(1, after.count { it.id == 1L })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun mergeRestore_preservesUntouchedClassificationsAndForcesFamilyForArham() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            seedContact(db, 1, "الأخت منى", category = Category.UNCATEGORIZED, tags = emptyList())
            seedContact(db, 2, "زميل العمل", category = Category.WORK, tags = listOf("مكتب"))

            val rewrite1 = db.observeContacts().first().first { it.id == 1L }.copy(
                // عهد upsertArhamInfo: إسناد أرحام يُلزم التصنيف إلى FAMILY حصراً.
                category = Category.FAMILY,
                tags = listOf("عائلة"),
                arham = ArhamInfo(relation = Relation.SISTER, frequency = ArhamFrequency.OCCASIONS)
            )

            val outcome = db.applyClassificationRestore(
                ClassificationRestorePlan(
                    replaceBeforeApply = false,
                    replacementContacts = emptyList(),
                    contactRewrites = listOf(rewrite1)
                )
            )

            assertEquals(0, outcome.clearedContacts)
            assertEquals(listOf(1L), outcome.appliedContactIds)

            val after = db.observeContacts().first()
            val c1 = after.first { it.id == 1L }
            assertEquals(Category.FAMILY, c1.category)
            assertEquals(Relation.SISTER, c1.arham?.relation)
            assertEquals(listOf("عائلة"), c1.tags)
            // ما لم يلمسه الحمولة في وضع الدمج يبقى لا-ممَاسّ تماماً (عهد MERGE).
            val c2 = after.first { it.id == 2L }
            assertEquals(Category.WORK, c2.category)
            assertEquals(listOf("مكتب"), c2.tags)
            assertEquals(null, c2.arham)
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun replaceRestore_emptyDatabase_clearedZeroAndNoArhamBlast() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // دفتر فارغ مع وضع REPLACE: لا شطف يلزمه شيء — لا كتابة ولا إشعار أرحام
            // (عهد المسار القديم: deleteArhamInfo كان لكل جهة حية فحسب).
            val listenerHits = mutableListOf<String>()
            val listener = ArhamDataChangeListener { listenerHits += "hit" }
            db.addArhamDataChangeListener(listener)

            val outcome = db.applyClassificationRestore(
                ClassificationRestorePlan(
                    replaceBeforeApply = true,
                    replacementContacts = emptyList(),
                    contactRewrites = emptyList()
                )
            )

            db.removeArhamDataChangeListener(listener)

            assertEquals(0, outcome.clearedContacts)
            assertTrue(outcome.appliedContactIds.isEmpty() && outcome.missingContactIds.isEmpty())
            assertTrue(listenerHits.isEmpty(), "No arham delta implies no listener blast (old-path contract)")
        } finally {
            db.closeConnection()
        }
    }
}
