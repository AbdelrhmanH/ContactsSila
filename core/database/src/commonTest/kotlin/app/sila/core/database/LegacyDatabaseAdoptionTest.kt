package app.sila.core.database

import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.legacy.LegacyAdoptionException
import app.sila.core.database.legacy.LegacyBackupException
import app.sila.core.database.legacy.LegacyDatabaseAdopter
import app.sila.core.database.legacy.LegacyFtsDdl
import app.sila.core.database.legacy.LegacySchemaLevel
import app.sila.core.database.legacy.LegacySchemaUnknownException
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.database.legacy.SilaDatabaseBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات اعتماد قواعد `user_version = 0` (PR B · ADR-0011 §الاعتماد الآمن) —
 * المصفوفة الحرجة للإصدار: مستويات التجهيزات، حفظ السجلات حرفياً، والفشل الآمن.
 */
class LegacyDatabaseAdoptionTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    // ===== قراءات بنية خام للتحقق من عدم المساس =====

    private suspend fun contactsColumnCount(path: String): Int =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("PRAGMA table_info(contacts)").use { stmt ->
                var count = 0
                while (stmt.step()) count++
                count
            }
        }

    private suspend fun tableExists(path: String, name: String): Boolean =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = ?").use { stmt ->
                stmt.bindText(1, name)
                stmt.step() && stmt.getLong(0) > 0L
            }
        }

    private suspend fun contactsRowCount(path: String): Long =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("SELECT COUNT(*) FROM contacts").use { stmt ->
                if (stmt.step()) stmt.getLong(0) else -1L
            }
        }

    private suspend fun phonesRowCount(path: String): Long =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("SELECT COUNT(*) FROM contact_phones").use { stmt ->
                if (stmt.step()) stmt.getLong(0) else -1L
            }
        }

    private suspend fun ftsCreateSqlOf(path: String): String? =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("SELECT sql FROM sqlite_master WHERE name = 'contacts_fts'").use { stmt ->
                if (stmt.step() && !stmt.isNull(0)) stmt.getText(0) else null
            }
        }

    private suspend fun userVersionOf(path: String): Int =
        BundledSQLiteDriver().open(path).use { conn ->
            conn.prepare("PRAGMA user_version").use { stmt ->
                stmt.step()
                stmt.getLong(0).toInt()
            }
        }

    // ===== 1) L2/L3: توأمة كاملة مسبقاً (FTS سداسي) — اعتماد نظيف بلا إعادة بناء FTS =====

    @Test
    fun adoption_L2Fixture_fullParity_adoptsCleanly_backupDeletedAfterVerifiedRead_andReopensAsRoomManaged() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_l2_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, company, department, " +
                    "category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'l2_full', 'نور الدين مرسي', 'صيدلية النور', 'الصيدلة', " +
                    "'WORK', 0, 'نور الدين مرسي', 10, 20, '')"
            )
            conn.executeSQL(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (1, '01099998888', '+201099998888', 'MOBILE', 1)"
            )
        }
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db.observeContacts().first()
            assertEquals(1, loaded.size)
            assertEquals("نور الدين مرسي", loaded.single().name.displayName)
            assertTrue(db.searchContacts("الصيدلة").first().any { it.systemContactId == "l2_full" })
            // النسخة الواقية حُذفت بعد أول قراءة Room ناجحة (اعتماد موثق — ADR-0011):
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تُحذف بعد الاعتماد الموثق")
        } finally {
            db.closeConnection()
        }

        // إعادة الفتح: user_version = 1 الآن ⇒ ROOM_MANAGED — بلا شيم وبلا نسخة جديدة:
        val reopened = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, reopened.observeContacts().first().size)
            assertFalse(PlatformFileOps.fileExists(backupPath), "لا نسخة جديدة لقاعدة مُدارة من Room")
        } finally {
            reopened.closeConnection()
        }
    }

    // ===== 1-ب) FTS بخيارات أجنبية (توكنِزِر porter — أعمدة الستّة مطابقة) ⇒ يُعاد بناؤه بالـ DDL الموحد =====

    @Test
    fun adoption_ftsWithForeignOptions_sameColumns_isRebuiltToCanonicalDdl_andStaysSearchable() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_fts_opts_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL("DROP TABLE contacts_fts")
            // أعمدة الهدف حرفياً لكن بخيارات أجنبية — كانت ستعبر بوابة الأعمدة حصراً (تحصين مراجعة GLM):
            conn.executeSQL(
                "CREATE VIRTUAL TABLE contacts_fts USING fts4(" +
                    "displayName, nickname, company, department, notes, normalizedName, tokenize=porter)"
            )
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, company, department, " +
                    "category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'l2_fts_opts', 'نور الدين مرسي', 'صيدلية النور', 'الصيدلة', " +
                    "'WORK', 0, 'نور الدين مرسي', 10, 20, '')"
            )
        }
        assertTrue(
            ftsCreateSqlOf(tempDbPath)?.contains("tokenize=porter") == true,
            "التجهيز يحتوي فعلاً FTS بتوكنِزِر أجنبي قبل الاعتماد",
        )
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            // الشيم اكتشف خيارات FTS الأجنبية فأعاد بناء الجدول بالـ DDL الموحد وأعاد التعبئة:
            val liveCreateSql = ftsCreateSqlOf(tempDbPath)
            assertTrue(liveCreateSql != null && LegacyFtsDdl.optionsOf(liveCreateSql) == LegacyFtsDdl.TARGET_OPTIONS,
                "خيارات FTS بعد الاعتماد مطابقة للثابت الموحد (بلا tokenize=/content=)")
            assertEquals(1, db.observeContacts().first().size)
            assertTrue(db.searchContacts("الصيدلة").first().any { it.systemContactId == "l2_fts_opts" })
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تُحذف بعد الاعتماد الموثق")
        } finally {
            db.closeConnection()
        }

        // إعادة الفتح: ROOM_MANAGED — بلا شيم ثانية:
        val reopened = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, reopened.observeContacts().first().size)
            assertFalse(PlatformFileOps.fileExists(backupPath))
        } finally {
            reopened.closeConnection()
        }
    }

    // ===== 2) تكرارات systemContactId — يُمنع الدمج (Decision 2): كل الصفوف تُحفظ =====

    @Test
    fun adoption_preservesDuplicateSystemContactId_rowsVerbatim_noMergeNoLoss() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_dup_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            // صفّان بنفس systemContactId (حالة حقيقية محتملة) + صف ثالث عادي:
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'dup_key', 'أحمد الأول', 'WORK', 0, 'أحمد الأول', 1, 2)"
            )
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (2, 'dup_key', 'أحمد الثاني', 'FRIENDS', 1, 'أحمد الثاني', 3, 4)"
            )
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (3, 'other_key', 'منى صبري', 'FAMILY', 0, 'منى صبري', 5, 6)"
            )
        }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db.observeContacts().first()
            assertEquals(3, loaded.size, "لا دمج ولا إزالة تكرار أثناء الاعتماد")
            val duplicates = loaded.filter { it.systemContactId == "dup_key" }.sortedBy { it.id }
            assertEquals(listOf(1L, 2L), duplicates.map { it.id }, "الصفّان المكرران بنفس هويتيهما")
            assertEquals("أحمد الأول", duplicates[0].name.displayName)
            assertEquals("أحمد الثاني", duplicates[1].name.displayName)
            assertEquals("منى صبري", loaded.single { it.systemContactId == "other_key" }.name.displayName)
        } finally {
            db.closeConnection()
        }

        // الحفظ دائم على القرص — إعادة الفتح تُظهر الصفوف الثلاثة نفسها:
        val reopened = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(3, reopened.observeContacts().first().size)
            assertEquals(3L, contactsRowCount(tempDbPath))
        } finally {
            reopened.closeConnection()
        }
    }

    // ===== 3) العربية والرموز التعبيرية — حفظ حرفي + قابلية بحث بعد الاعتماد =====

    @Test
    fun adoption_preservesArabicDiacriticsAndEmoji_verbatim_andSearchableAfterBackfill() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_rtl_${Random.nextLong(100_000, 999_999)}.db")
        val displayName = "سُعاد المُبَرّرة 🌟"
        val company = "مكتب الزهراء ⭐"

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            // نفس مسار الكتابة التاريخي: normalizedName مُعرَّب بالمعيِّر نفسه.
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, company, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'rtl_emoji', '$displayName', '$company', 'WORK', 0, '${ArabicTextNormalizer.normalize(displayName)}', 1, 2)"
            )
        }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db.observeContacts().first().single()
            assertEquals(displayName, loaded.name.displayName, "حفظ حرفي — تشكيل ورموز تعبيرية كما هي")
            assertEquals(company, loaded.organization?.company, "الشركة بالرمز التعبيري كما هي")

            // البحث المعياري يعمل فوق الفهرس المُعاد تعبئته (همزات/تشكيل):
            val hits = db.searchContacts("سعاد").first()
            assertTrue(hits.any { it.systemContactId == "rtl_emoji" }, "المطابقة العربية بعد الاعتماد")
        } finally {
            db.closeConnection()
        }
    }

    // ===== 4) فشل آمن: مخطط غير مصنَّف — بلا أي تعديل + نسخة تشخيصية =====

    @Test
    fun adoption_unknownSchema_failsClosed_leavesDatabaseUntouched_andCreatesDiagnosticBackup() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_unknown_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'u1', 'لم يُمس', 'WORK', 0, 'لم يمس', 1, 2)"
            )
            conn.executeSQL("INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (1, '01011112222', '+201011112222', 'MOBILE', 1)")
            // جدول خارج الهدف ⇒ UNKNOWN:
            conn.executeSQL("CREATE TABLE user_settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        val adopter = LegacyDatabaseAdopter(tempDbPath)
        val exception = assertFailsWith<LegacySchemaUnknownException> {
            adopter.adoptIfNeeded()
        }
        assertTrue(exception.message?.contains("fail-closed") == true)

        // القاعدة لم تُعدَّل إطلاقاً: 13 عموداً، بلا نقاط استئناف، السجلات كما هي، الجدول الغريب باقٍ:
        assertEquals(13, contactsColumnCount(tempDbPath), "بلا أعمدة توأمة — الشيم لم يعمل")
        assertFalse(tableExists(tempDbPath, "contacts_sync_checkpoints"), "بلا شيم إطلاقاً")
        assertEquals(1L, contactsRowCount(tempDbPath))
        assertEquals(1L, phonesRowCount(tempDbPath))
        assertTrue(tableExists(tempDbPath, "user_settings"))
        // نسخة تشخيصية واقية أُنشئت قبل الرمي (أفضل جهد حفظ):
        assertTrue(PlatformFileOps.fileExists(backupPath), "نسخة تشخيصية قبل الفشل الآمن")
        assertTrue(SilaDatabaseBackup.verifiedBackupExists(tempDbPath))
    }

    @Test
    fun adoption_unknownInvariantTableColumn_failsClosed_beforeRunningShim() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_unknown_col_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'u_col', 'سلامة الأعمدة', 'WORK', 0, 'سلامه الاعمده', 1, 2)"
            )
            // عمود غير معروف في جدول غير contacts (مثل services) ⇒ UNKNOWN قبل الشيم:
            conn.executeSQL("ALTER TABLE services ADD COLUMN unexpected_col TEXT")
        }

        val adopter = LegacyDatabaseAdopter(tempDbPath)
        assertFailsWith<LegacySchemaUnknownException> {
            adopter.adoptIfNeeded()
        }

        // الشيم لم يعمل إطلاقاً: contacts لا تزال 13 عموداً وبلا نقاط استئناف:
        assertEquals(13, contactsColumnCount(tempDbPath), "فشل آمن قبل الشيم عند انحراف أعمدة جدول آخر")
        assertFalse(tableExists(tempDbPath, "contacts_sync_checkpoints"))
        assertEquals(1L, contactsRowCount(tempDbPath))
        assertTrue(SilaDatabaseBackup.verifiedBackupExists(tempDbPath))
    }

    // ===== 5) فشل الشيم المحقون — تراجع كامل + النسخة باقية + استئناف ناجح بعدها =====

    @Test
    fun adoption_injectedShimFailure_rollsBackFully_retainsBackup_andRecoversOnSecondRun() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_shimfail_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'sf1', 'قبل الفشل', 'WORK', 0, 'قبل الفشل', 1, 2)"
            )
            conn.executeSQL("INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (1, '01077776666', '+201077776666', 'MOBILE', 1)")
        }

        val adopter = LegacyDatabaseAdopter(tempDbPath)
        adopter.shimFaultHook = { IllegalStateException("injected-shim-failure") }
        val exception = assertFailsWith<LegacyAdoptionException> {
            adopter.adoptIfNeeded()
        }
        assertTrue(exception.cause is IllegalStateException, "سبب التراجع هو الخطاف المحقون")
        adopter.shimFaultHook = null

        // التراجع كامل: الحالة الفيزيائية كما كانت قبل الشيم حرفياً + النسخة الواقية باقية:
        assertEquals(13, contactsColumnCount(tempDbPath), "ROLLBACK كامل — بلا أعمدة توأمة")
        assertFalse(tableExists(tempDbPath, "contacts_sync_checkpoints"))
        assertEquals(1L, contactsRowCount(tempDbPath))
        assertEquals(1L, phonesRowCount(tempDbPath))
        assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تُحفظ بعد الفشل")

        // الاستئناف: الاعتماد الثاني (بلا حقن) ينجح — الشيم idempotent:
        val result = adopter.adoptIfNeeded()
        assertEquals(LegacySchemaLevel.L0, result.detectedLevel)
        assertTrue(tableExists(tempDbPath, "contacts_sync_checkpoints"), "الشيم اكتمل في المحاولة الثانية")
        assertEquals(1L, contactsRowCount(tempDbPath), "بلا فقدان بيانات بعد الاستئناف")
        assertTrue(SilaDatabaseBackup.verifiedBackupExists(tempDbPath), "نسخة سابقة موثقة تكفي — بلا إعادة نسخ")
    }

    // ===== 6) فشل النسخة الواقية — فشل آمن قبل أي تعديل (مساحة/مسار غير صالح) =====

    @Test
    fun adoption_backupStepFailure_throwsLegacyBackupException_andTouchesNothing() = runTest {
        // مجلد غير موجود ⇒ فحص المساحة يفشل مغلوقاً (usableSpace = 0 < المطلوب):
        val missingDir = PlatformFileOps.tempTestDbPath("tmp_missing_dir_${Random.nextLong(100_000, 999_999)}")
        val unreachableDbPath = "$missingDir/sila_local.db"

        val exception = assertFailsWith<LegacyBackupException> {
            SilaDatabaseBackup.createVerifiedBackup(unreachableDbPath)
        }
        assertTrue(exception.message?.contains("insufficient storage") == true)
        // لم يُنشأ أي ملف قاعدة — فشل قبل أي لمس:
        assertFalse(PlatformFileOps.fileExists(unreachableDbPath))
    }

    // ===== 7) ختم الإصدار: أول فتح Room ناجح يُسجّل user_version = 1 + جدول الهوية =====

    @Test
    fun adoption_firstSuccessfulRoomOpen_stampsUserVersion1_withRoomMasterIdentity() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_adopt_stamp_${Random.nextLong(100_000, 999_999)}.db")

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'stamp_1', 'ختم الإصدار', 'WORK', 0, 'ختم الإصدار', 1, 2, '')"
            )
        }
        assertEquals(0, userVersionOf(tempDbPath), "القاعدة القديمة تدخل الاعتماد بـ user_version = 0")

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, db.observeContacts().first().size)
        } finally {
            db.closeConnection()
        }

        // Decision 3 (ADR-0011): أول إصدار Room فعلي = 1 — يُختم عبر مسار create/validate
        // لإصدار-صفر، وليس Migration(0,1).
        assertEquals(1, userVersionOf(tempDbPath), "Room يختم user_version = 1 عند أول فتح ناجح")
        assertTrue(tableExists(tempDbPath, "room_master_table"), "جدول هوية Room مُنشأ")
        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            conn.prepare("SELECT identity_hash FROM room_master_table").use { stmt ->
                assertTrue(stmt.step(), "صف هوية Room موجود")
                assertTrue(stmt.getText(0).isNotBlank(), "بصمة الهوية غير فارغة")
            }
        }
    }

    // ===== 8) تثبيت جديد: Room ينشئ المخطط النهائي مباشرة — بلا DDL يدوي وبلا جداول شبحية =====

    @Test
    fun freshInstall_roomCreatesFinalSchemaDirectly_version1_noPhantomTables() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_fresh_${Random.nextLong(100_000, 999_999)}.db")
        assertFalse(PlatformFileOps.fileExists(tempDbPath), "التثبيت الجديد يبدأ بلا ملف قاعدة")

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertTrue(db.observeContacts().first().isEmpty())
            assertTrue(db.searchContacts("أي شيء").first().isEmpty())
        } finally {
            db.closeConnection()
        }

        assertEquals(1, userVersionOf(tempDbPath), "المخطط الفعلي الأول يُنشأ منشأً عند الإصدار 1")
        // الجداول الثمانية المُدارة + FTS المستقل + جدول هوية Room:
        val managedTables = listOf(
            "contacts", "contact_phones", "arham", "services", "call_events",
            "arham_inbox", "dispute_logs", "contacts_sync_checkpoints",
        )
        managedTables.forEach { table ->
            assertTrue(tableExists(tempDbPath, table), "جدول مُدار منشأ: $table")
        }
        assertTrue(tableExists(tempDbPath, "contacts_fts"), "FTS4 المستقل مُنشأ عبر Callback")
        assertTrue(tableExists(tempDbPath, "room_master_table"), "جدول هوية Room مُنشأ")
        // الكيانان الشبحيان لا يُنشآن أبداً (Decision 5 — ADR-0011):
        assertFalse(tableExists(tempDbPath, "scheduled_messages"), "بلا جدول شبحي scheduled_messages")
        assertFalse(tableExists(tempDbPath, "emergency_ringing_sessions"), "بلا جدول شبحي emergency_ringing_sessions")
        // مخطط contacts الناتج يطابق التصدير (29 عموداً):
        assertEquals(29, contactsColumnCount(tempDbPath), "contacts بـ 29 عموداً مطابقاً للمخطط المُصدَّر")
        // DDL الفعلي لجدول مُدار مصدره مولِّد Room حصراً:
        val contactsCreateSql = BundledSQLiteDriver().open(tempDbPath).use { conn ->
            conn.prepare("SELECT sql FROM sqlite_master WHERE name = 'contacts'").use { stmt ->
                stmt.step()
                stmt.getText(0)
            }
        }
        assertTrue(contactsCreateSql.contains("AUTOINCREMENT"), "contacts.id مولَّد من الكيان (autoGenerate) — لا DDL يدوي")
    }
}
