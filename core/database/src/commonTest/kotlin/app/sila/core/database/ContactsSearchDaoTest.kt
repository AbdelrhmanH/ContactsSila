package app.sila.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات مسار البحث الإنتاجي FTS4 + الأفعال الجماعية + ترحيل فهرس T3 (Issue #24).
 *
 * التوكنة والتعريب مُختبران تجريبياً هنا (ArabicTextNormalizer: توحيد الهمزات،
 * إسقاط التشكيل، تحويل الأرقام العربية-الهندية إلى غربية) — بلا ادعاءات فونيتك
 * أو مطابقة عربية مرنة غير منفّذة.
 *
 * **بذور العرض:** قاعدة حديثة/فارغة تُبذّر بجهات عرض افتراضية (`seedInitialContacts`)
 * — سلوك منتج قائم. لذلك كل التأكيدات هنا **نسبية لعدّادات الاختبار** (بادئة
 * `t3_` في `systemContactId`) لا مطلقة على حجم الجدول.
 */
class ContactsSearchDaoTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private fun contact(
        systemContactId: String,
        displayName: String,
        phones: List<Phone> = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
        nickname: String? = null,
        company: String? = null,
        department: String? = null,
        notes: String? = null,
        category: Category = Category.UNCATEGORIZED,
        isFavorite: Boolean = false,
    ): Contact = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName, nickname = nickname),
        phones = phones,
        organization = if (company != null || department != null) {
            Organization(company = company, department = department)
        } else {
            null
        },
        notes = notes,
        category = category,
        isFavorite = isFavorite,
        normalizedName = displayName
    )

    /** نتائج البحث منسوبة لعدّادات هذا الاختبار حصراً (البذور مستبعدة من التأكيدات). */
    private suspend fun LocalSilaDatabase.fixtureResults(query: String, fixtureIds: Set<String>): List<Contact> =
        searchContacts(query).first().filter { it.systemContactId in fixtureIds }

    // ===== البحث =====

    @Test
    fun searchContacts_matchesArabicAndEnglishNames_viaFts() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_ar", "أحمد سيد"))
            db.upsertContact(contact("t3_en", "Ahmed Ali"))
            db.upsertContact(contact("t3_mo", "منى عمر"))

            val ahmad = db.fixtureResults("أحمد", setOf("t3_ar", "t3_en", "t3_mo"))
            assertEquals(listOf("t3_ar"), ahmad.map { it.systemContactId }, "مطابقة عربية مباشرة عبر FTS")

            val english = db.fixtureResults("ahmed", setOf("t3_ar", "t3_en"))
            assertEquals(listOf("t3_en"), english.map { it.systemContactId }, "إنجليزي (أحرف صغيرة من المُطبّع)")

            val partial = db.fixtureResults("من", setOf("t3_ar", "t3_mo"))
            assertEquals(listOf("t3_mo"), partial.map { it.systemContactId }, "بادئة جزئية")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_hamzaVariantsMatch_viaNormalizedNameColumn() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // "أحمد" بهمزة — الاستعلام المتعارب "احمد" (ألف مجردة) يجب أن يطابق عبر
            // عمود normalizedName المُوحَّد (توحيد الهمزات في ArabicTextNormalizer).
            db.upsertContact(contact("t3_hamza", "أحمد"))

            val byPlainAlef = db.fixtureResults("احمد", setOf("t3_hamza"))
            assertTrue(byPlainAlef.any { it.systemContactId == "t3_hamza" }, "توحيد الهمزات عبر normalizedName")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_matchesPhoneFragments_inRawAndE164() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(
                contact(
                    "t3_phone", "هشام",
                    phones = listOf(Phone(raw = "01033334444", e164 = "+201033334444", isPrimary = true))
                )
            )
            db.upsertContact(contact("t3_nophone", "منى"))
            val fixtures = setOf("t3_phone", "t3_nophone")

            val byE164Fragment = db.fixtureResults("3333", fixtures)
            assertEquals(listOf("t3_phone"), byE164Fragment.map { it.systemContactId }, "جزء من الرقم عبر LIKE")

            // أرقام عربية-هندية تُحوَّل إلى غربية في المُطبّع:
            val byArabicDigits = db.fixtureResults("٠١٠٣", fixtures)
            assertTrue(byArabicDigits.any { it.systemContactId == "t3_phone" }, "تعريب الأرقام")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_matchesCompanyNicknameDepartmentNotes() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_company", "صاحب الشركة", company = "مكتب النور المضيء"))
            db.upsertContact(contact("t3_nickname", "عبد الله", nickname = "أبو زياد قاف"))
            db.upsertContact(
                contact(
                    "t3_department", "موظف القسم",
                    company = "شركة سِلة المحدودة",
                    department = "الحسابات المالية"
                )
            )
            db.upsertContact(contact("t3_notes", "جهة عادية", notes = "يعمل فني تكييف في مصر الجديدة"))

            val fixtures = setOf("t3_company", "t3_nickname", "t3_department", "t3_notes")
            assertTrue(
                db.fixtureResults("المضيء", fixtures).any { it.systemContactId == "t3_company" },
                "الشركة"
            )
            assertTrue(
                db.fixtureResults("قاف", fixtures).any { it.systemContactId == "t3_nickname" },
                "اللقب"
            )
            assertTrue(
                db.fixtureResults("المالية", fixtures).any { it.systemContactId == "t3_department" },
                "القسم (عمود T3)"
            )
            assertTrue(
                db.fixtureResults("تكييف", fixtures).any { it.systemContactId == "t3_notes" },
                "الملاحظات"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_blankOrWhitespace_returnsAllLive_excludesTombstoned() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_live", "أحمد سيد"))
            db.upsertContact(contact("t3_dead", "منى عمر"))

            db.applySyncBatch(
                upserts = emptyList(),
                tombstones = listOf("t3_dead"),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = 1_800_000_000_000L
            )

            listOf("", "   ", "\t\n").forEach { blank ->
                val result = db.searchContacts(blank).first().filter { it.systemContactId.startsWith("t3_") }
                assertEquals(listOf("t3_live"), result.map { it.systemContactId }, "استعلام فارغ «$blank» = كل الجهات الحيّة")
                assertTrue(
                    db.searchContacts(blank).first().none { it.systemContactId == "t3_dead" },
                    "القبور مستبعدة دائماً"
                )
            }
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_specialCharsOnly_returnsEmpty_andKnownTokenStillWorks() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_mix", "أحمد"))

            // محارف FTS مشغّلة بلا أي رمز — بلا استثناء وبلا تطابق:
            listOf("***", "\"", "()-:", "@#$", "  **  ").forEach { junk ->
                val result = db.searchContacts(junk).first()
                assertTrue(result.isEmpty(), "محارف خاصة «$junk» ⇒ لا نتائج (لا انفجار ولا كل الجدول)")
            }

            // مزيج صحيح بعد التنقية: أحرف + مشغّلات ⇒ يطابق الرمز المُنقّى:
            val mixed = db.searchContacts("أحمد*").first()
            assertTrue(mixed.any { it.systemContactId == "t3_mix" })
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_noResults_forUnknownToken_andRepeatedQueriesStable() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_stable", "أحمد سيد"))

            val unknown = db.searchContacts("زيادالمجهول").first().filter { it.systemContactId.startsWith("t3_") }
            assertTrue(unknown.isEmpty())

            // الاستعلام المتكرر (معيار القبول) يبقى ثابتاً:
            val first = db.fixtureResults("سيد", setOf("t3_stable"))
            val second = db.fixtureResults("سيد", setOf("t3_stable"))
            assertEquals(first.map { it.id }, second.map { it.id })
            assertTrue(first.isNotEmpty())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun searchContacts_seesLiveUpdates_afterUpsertAndTombstone() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_one", "أحمد سيد"))
            assertTrue(db.fixtureResults("سيد", setOf("t3_one")).any { it.systemContactId == "t3_one" })

            // جهة جديدة بعد المزامنة تظهر فوراً (الكاش يتبعها):
            db.upsertContact(contact("t3_two", "سيدني برشلونة"))
            assertTrue(db.fixtureResults("سيد", setOf("t3_one", "t3_two")).any { it.systemContactId == "t3_two" })

            // القبور تختفي من البحث:
            db.applySyncBatch(
                upserts = emptyList(),
                tombstones = listOf("t3_two"),
                snapshotJson = null,
                reconcileStampMillis = null,
                nowMillis = 1_800_000_000_000L
            )
            assertTrue(db.fixtureResults("سيد", setOf("t3_two")).isEmpty())
        } finally {
            db.closeConnection()
        }
    }

    // ===== المفضلة والأفعال الجماعية =====

    @Test
    fun setFavorite_persistsOnDisk_andSurvivesReopen() = runTest {
        val tempDbPath = "build/tmp_test_fav_${Random.nextLong(100_000, 999_999)}.db"
        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_fav", "أحمد"))
            val id = db.observeContacts().first().single { it.systemContactId == "t3_fav" }.id
            db.setFavorite(id, isFavorite = true)
            assertTrue(db.observeFavorites().first().any { it.id == id })
        } finally {
            db.closeConnection()
        }

        // إعادة الفتح: عمود صِلَة محفوظ فعلياً — المفضلة باقية (معيار القبول).
        val reopened = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val favorites = reopened.observeFavorites().first()
            val mine = favorites.singleOrNull { it.systemContactId == "t3_fav" }
            assertTrue(mine != null, "المفضلة نجت من إغلاق وفتح القاعدة")
            assertTrue(mine.isFavorite)
        } finally {
            reopened.closeConnection()
        }
    }

    @Test
    fun bulkOperations_categorizeFavoriteDelete_handleEmptyAndUnknownIds() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_bulk1", "أحمد", category = Category.WORK))
            db.upsertContact(contact("t3_bulk2", "منى"))
            val all = db.observeContacts().first()
            val id1 = all.single { it.systemContactId == "t3_bulk1" }.id
            val id2 = all.single { it.systemContactId == "t3_bulk2" }.id

            // المجموعة الفارغة بلا أثر وبلا استثناء:
            db.updateCategories(emptyList(), Category.FAMILY)
            db.setFavorites(emptyList(), true)
            db.deleteContacts(emptyList())
            assertEquals(2, db.observeContacts().first().count { it.systemContactId.startsWith("t3_") })

            // تصنيف + مفضلة جماعيان — ومعرّفات مجهولة لا أثر لها:
            db.updateCategories(listOf(id1, id2, 999_999L), Category.FAMILY)
            db.setFavorites(listOf(id2, 888_888L), isFavorite = true)

            val byCategory = db.observeContacts(category = Category.FAMILY).first()
                .filter { it.systemContactId.startsWith("t3_") }
            assertEquals(setOf(id1, id2), byCategory.map { it.id }.toSet())

            val favorites = db.observeFavorites().first().filter { it.systemContactId.startsWith("t3_") }
            assertEquals(listOf(id2), favorites.map { it.id })

            // حذف جماعي فعلي — يزيل الصفوف والهواتف ويُحدّث الكاش والفهرس:
            db.deleteContacts(listOf(id1, id2))
            assertTrue(db.observeContacts().first().none { it.systemContactId.startsWith("t3_") })
            assertTrue(db.fixtureResults("أحمد", setOf("t3_bulk1")).isEmpty())
            assertTrue(db.fixtureResults("3333", setOf("t3_bulk1")).isEmpty())
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun categoryCounts_stayConsistentWithList_afterBulkCategorize() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            db.upsertContact(contact("t3_cnt1", "أحمد", category = Category.WORK))
            db.upsertContact(contact("t3_cnt2", "منى", category = Category.WORK))
            db.upsertContact(contact("t3_cnt3", "سيد"))
            val all = db.observeContacts().first()
            val id1 = all.single { it.systemContactId == "t3_cnt1" }.id
            val id2 = all.single { it.systemContactId == "t3_cnt2" }.id

            val countsBefore = db.observeCategoryCounts().first()
            db.updateCategories(listOf(id1, id2), Category.FAMILY)
            val countsAfter = db.observeCategoryCounts().first()

            assertEquals(
                (countsBefore[Category.FAMILY] ?: 0) + 2,
                countsAfter[Category.FAMILY],
                "العدادات تتبع التصنيف الجماعي فوراً (نسبة للبذور)"
            )
            assertEquals(
                db.observeContacts().first().size,
                countsAfter.values.sum(),
                "ثبات المتساوية: مجموع العدادات = عدد الجهات الحيّة"
            )
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun recordInAppCallAttempt_persistsLocally_andIsSubjectToRetention() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        try {
            // محاولة قديمة (أقدم من 365 يوماً) — يثبت التسجيل ثم يلتقطها التنظيف الدوري:
            val oldTimestamp = 1_800_000_000_000L - (365L + 5) * 24 * 60 * 60 * 1000
            db.recordInAppCallAttempt(
                callLogId = "inapp-test-1",
                e164 = "+201000000000",
                timestampMillis = oldTimestamp
            )
            val report = db.cleanupExpiredData(nowMillis = 1_800_000_000_000L)
            assertTrue(report.deletedCallEvents >= 1, "محاولة IN_APP سُجلت فعلاً في call_events")
        } finally {
            db.closeConnection()
        }
    }

    // ===== ترحيل فهرس T3 (إضافة عمود department بإعادة الإنشاء) =====

    @Test
    fun migration_preT3Fts_recreatesWithDepartment_andBackfillsFromContacts() = runTest {
        val tempDbPath = "build/tmp_test_fts_t3_${Random.nextLong(100_000, 999_999)}.db"

        // 1) قاعدة حديثة (مخطط T3) — بذور العرض تُكتب في الملف + جهة لها قسم:
        val fresh = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            fresh.upsertContact(
                contact("t3_dept", "هشام عبد الحميد", company = "مكتب النور", department = "الحسابات المالية")
            )
        } finally {
            fresh.closeConnection()
        }

        // 2) تقليض الفهرس يدوياً إلى مخطط ما قبل T3 (5 أعمدة بلا department):
        val old = BundledSQLiteDriver().open(tempDbPath)
        try {
            old.execSQL("DROP TABLE contacts_fts")
            old.execSQL(
                "CREATE VIRTUAL TABLE contacts_fts USING fts4(" +
                    "displayName, nickname, company, notes, normalizedName)"
            )
            old.execSQL(
                "INSERT INTO contacts_fts (docid, displayName, nickname, company, notes, normalizedName) " +
                    "SELECT id, displayName, nickname, company, notes, normalizedName FROM contacts"
            )
        } finally {
            old.close()
        }

        // 3) إعادة الفتح: الكشف موجَّه بالشكل ⇒ إسقاط وإعادة إنشاء وإعادة تعبئة idempotent:
        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db.observeContacts().first()
            assertTrue(loaded.any { it.systemContactId == "t3_dept" }, "لا فقدان بيانات في الترحيل")

            val byDepartment = db.fixtureResults("المالية", setOf("t3_dept"))
            assertTrue(byDepartment.any { it.systemContactId == "t3_dept" }, "عمود department مُفهرس بعد الترحيل")
            val byName = db.fixtureResults("هشام", setOf("t3_dept"))
            assertTrue(byName.any { it.systemContactId == "t3_dept" }, "بقية الأعمدة تعمل بعد إعادة الإنشاء")
        } finally {
            db.closeConnection()
        }
    }
}
