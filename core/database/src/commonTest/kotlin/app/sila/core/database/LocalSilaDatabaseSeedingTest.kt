package app.sila.core.database

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات انحدار F-01: تهيئة الإنتاج **لا تُدرج بيانات العرض التجريبية أبداً**،
 * والبذر متاح للاختبارات حصراً عبر تصريح صريح (`seedDemoData = true`)،
 * وبيانات المستخدم القائمة لا تُمسّ ولا تُكرَّر مهما تكررت التهيئة.
 */
class LocalSilaDatabaseSeedingTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private fun userContact(id: Long = 99L, systemContactId: String = "user_real_1") = Contact(
        id = id,
        systemContactId = systemContactId,
        name = ContactName(displayName = "جهة مستخدم حقيقية"),
        phones = listOf(Phone(raw = "01077778888", e164 = "+201077778888", isPrimary = true)),
        category = Category.FAMILY,
        normalizedName = "جهة مستخدم حقيقية"
    )

    @Test
    fun productionInitialization_startsEmpty_withoutAnyDemoRows() = runTest {
        // الافتراضي = سلوك الإنتاج (seedDemoData = false): قاعدة فارغة تبقى فارغة.
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            assertTrue(db.observeContacts().first().isEmpty(), "التهيئة الإنتاجية يجب ألا تُدرج أي جهات عرض")
            assertTrue(db.observeServices().first().isEmpty(), "التهيئة الإنتاجية يجب ألا تُدرج أي خدمات عرض")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun optInSeeding_yieldsTheDemoFixture_exclusively() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers, seedDemoData = true)
        try {
            val contacts = db.observeContacts().first()
            assertEquals(7, contacts.size, "البذر الصريح يُنتج تركيبة العرض المعروفة (7 جهات)")
            assertTrue(contacts.any { it.name.displayName == "العم أحمد" }, "تركيبة العرض تتضمن «العم أحمد»")
            assertTrue(contacts.all { it.systemContactId.startsWith("sys_") }, "بذور العرض معرّفة ببادئة sys_")
            assertEquals(3, db.observeServices().first().size, "البذر الصريح يُنتج 3 خدمات عرض")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun existingUserData_survivesReinitialization_andNoDemoRowsAppear() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_seeding_${Random.nextLong(100_000, 999_999)}.db")

        // جلسة أولى: مستخدم يدخل جهته الحقيقية ثم يُغلق التطبيق.
        val first = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        first.upsertContact(userContact())
        first.closeConnection()

        // جلسة ثانية (إقلاع جديد، بلا بذر — سلوك الإنتاج): بيانات المستخدم باقية ولا بذور.
        val second = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val contacts = second.observeContacts().first()
            assertEquals(1, contacts.size, "بيانات المستخدم القائمة هي الوحيدة بعد إعادة التهيئة")
            assertEquals("user_real_1", contacts.single().systemContactId)
            assertFalse(contacts.any { it.systemContactId.startsWith("sys_") }, "لا بذور عرض تُحقن فوق بيانات المستخدم")
        } finally {
            second.closeConnection()
        }
    }

    @Test
    fun repeatedInitialization_neverDuplicatesRecords_evenWithOptInSeeding() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_seeding_${Random.nextLong(100_000, 999_999)}.db")

        // إقلاع أول ببذر صريح (محاكاة عالم اختبار) — يبذر 7 جهات.
        val first = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers, seedDemoData = true)
        assertEquals(7, first.observeContacts().first().size)
        first.closeConnection()

        // إقلاع ثانٍ بنفس العلم: الجداول غير فارغة فلا يُعاد البذر — لا تكرار.
        val second = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers, seedDemoData = true)
        try {
            val contacts = second.observeContacts().first()
            assertEquals(7, contacts.size, "إعادة التهيئة على قاعدة مُبذَّرة لا تضاعف السجلات")
            assertEquals(7, contacts.map { it.id }.toSet().size, "كل جهة بمعرّف فريد — لا صفوف مكررة")
        } finally {
            second.closeConnection()
        }
    }

    @Test
    fun userRowPlusOptInSeeding_userRowIsAuthoritative_andSeedingSkipsNonEmptyTables() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_seeding_${Random.nextLong(100_000, 999_999)}.db")

        // جلسة أولى بلا بذر: المستخدم يدخل جهته (جدول غير فارغ الآن).
        val first = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        first.upsertContact(userContact())
        first.closeConnection()

        // جلسة ثانية بعلم البذر (خطأ تهيئة محتمل لعالم اختبار): لا يجوز المسّ ولا البذر فوق بيانات المستخدم.
        val second = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers, seedDemoData = true)
        try {
            val contacts = second.observeContacts().first()
            assertEquals(1, contacts.size, "جدول غير فارغ لا يُبذَّر أبداً — بيانات المستخدم خط أحمر")
            assertEquals(99L, contacts.single().id)
        } finally {
            second.closeConnection()
        }
    }
}
