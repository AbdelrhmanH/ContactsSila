package app.sila.core.database

import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.AdoptionVerificationStage
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.legacy.LegacyAdoptionException
import app.sila.core.database.legacy.LegacyBackupException
import app.sila.core.database.legacy.LegacyDatabaseAdopter
import app.sila.core.database.legacy.LegacyFtsDdl
import app.sila.core.database.legacy.LegacyIndex
import app.sila.core.database.legacy.LegacySchemaFingerprint
import app.sila.core.database.legacy.LegacySchemaLevel
import app.sila.core.database.legacy.LegacySchemaSnapshot
import app.sila.core.database.legacy.LegacySchemaUnknownException
import app.sila.core.database.legacy.PlatformFileOps
import app.sila.core.database.legacy.SilaDatabaseBackup
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation
import app.sila.core.model.ServiceVisibility
import kotlinx.coroutines.CancellationException
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

        // إعادة الفتح: user_version = إصدار Room الآن ⇒ ROOM_MANAGED — بلا شيم وبلا نسخة جديدة:
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

    // ===== 7) ختم الإصدار: أول فتح Room ناجح يُسجّل إصدار المخطط + جدول الهوية =====

    @Test
    fun adoption_firstSuccessfulRoomOpen_stampsRoomSchemaVersion_withRoomMasterIdentity() = runTest {
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

        // Decision 3 (ADR-0011): أول إصدار Room فعلي يُختم عبر مسار create/validate
        // لإصدار-صفر، وليس Migration — مع T6 الإصدار الحالي SILA_ROOM_VERSION = 2
        // (v1→v2 عبر MIGRATION_1_2 يطبَّق في نفس الفتح الأول للقاعدة المعتمدة).
        assertEquals(SILA_ROOM_VERSION, userVersionOf(tempDbPath), "Room يختم إصدار مخططه عند أول فتح ناجح")
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
    fun freshInstall_roomCreatesFinalSchemaDirectly_noPhantomTables() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_fresh_${Random.nextLong(100_000, 999_999)}.db")
        assertFalse(PlatformFileOps.fileExists(tempDbPath), "التثبيت الجديد يبدأ بلا ملف قاعدة")

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertTrue(db.observeContacts().first().isEmpty())
            assertTrue(db.searchContacts("أي شيء").first().isEmpty())
        } finally {
            db.closeConnection()
        }

        assertEquals(SILA_ROOM_VERSION, userVersionOf(tempDbPath), "المخطط الفعلي يُنشأ منشأً عند إصدار Room الحالي")
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

    // ===== 9) SR-01: تطبيع الفهارس الفريدة بدقة (Fixtures A–E + Idempotency + حفظ السجلات) =====

    private suspend fun readContactsIndices(path: String): List<LegacyIndex> =
        BundledSQLiteDriver().open(path).use { conn ->
            LegacySchemaFingerprint.readIndicesForTable(conn, "contacts")
        }

    private suspend fun readSnapshotOf(path: String): LegacySchemaSnapshot =
        BundledSQLiteDriver().open(path).use { conn ->
            LegacySchemaFingerprint.readSnapshot(conn)
        }

    @Test
    fun sr01_fixtureA_normalizesOnlyLegacyUniqueSystemContactId_preservesLookupKeyUniqueIndex_andIsIdempotent() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr01_fixA_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL("DROP INDEX IF EXISTS idx_contacts_system_id")
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_system_id ON contacts(systemContactId)")
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_lookup_unique ON contacts(lookupKey)")
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, lookupKey, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (10, 'sys_a1', 'lk_1', 'أول', 'WORK', 0, 'اول', 1, 2, '')",
            )
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, lookupKey, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (20, 'sys_a2', 'lk_2', 'ثان', 'FAMILY', 1, 'ثان', 3, 4, '')",
            )
        }

        val adopter = LegacyDatabaseAdopter(tempDbPath)
        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            adopter.normalizeContactsIndices(conn)
        }

        val afterFirst = readContactsIndices(tempDbPath)
        val sysIdx = afterFirst.single { it.name == "idx_contacts_system_id" }
        assertFalse(sysIdx.unique, "فهرس systemContactId طُبِّع إلى غير فريد")
        assertEquals("c", sysIdx.origin)
        assertFalse(sysIdx.partial)
        assertEquals(listOf("systemContactId"), sysIdx.columns)
        assertEquals(listOf("ASC"), sysIdx.orders)

        val lookupIdx = afterFirst.single { it.name == "idx_contacts_lookup_unique" }
        assertTrue(lookupIdx.unique, "الفهرس الفريد المشروع على lookupKey محفوظ بالكامل")
        assertEquals("c", lookupIdx.origin)
        assertFalse(lookupIdx.partial)
        assertEquals(listOf("lookupKey"), lookupIdx.columns)
        assertEquals(listOf("ASC"), lookupIdx.orders)

        // التحقق من عدم تغيّر السجلات أو المعرّفات ومن التكافؤ عند التنفيذ الثاني (Idempotency):
        assertEquals(2L, contactsRowCount(tempDbPath))
        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            adopter.normalizeContactsIndices(conn)
        }
        val afterSecond = readContactsIndices(tempDbPath)
        assertEquals(afterFirst.toSet(), afterSecond.toSet(), "لا انحراف بعد التنفيذ الثاني")
        BundledSQLiteDriver().open(tempDbPath).use { conn ->
            conn.prepare("SELECT id, systemContactId, lookupKey FROM contacts ORDER BY id").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(10L, stmt.getLong(0))
                assertEquals("sys_a1", stmt.getText(1))
                assertEquals("lk_1", stmt.getText(2))
                assertTrue(stmt.step())
                assertEquals(20L, stmt.getLong(0))
                assertEquals("sys_a2", stmt.getText(1))
                assertEquals("lk_2", stmt.getText(2))
            }
        }
    }

    @Test
    fun sr01_legacyUniqueSystemContactId_fullAdoption_normalizesToNonUnique_andPreservesAllRecords() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr01_adopt_sys_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP INDEX IF EXISTS idx_contacts_system_id")
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_system_id ON contacts(systemContactId)")
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (7, 'sys_unique_legacy', 'كريم فؤاد', 'WORK', 0, 'كريم فؤاد', 1, 2, '')",
            )
        }

        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            val loaded = db.observeContacts().first()
            assertEquals(1, loaded.size)
            assertEquals(7L, loaded.single().id)
            assertEquals("sys_unique_legacy", loaded.single().systemContactId)
        } finally {
            db.closeConnection()
        }

        val indices = readContactsIndices(tempDbPath)
        val sysIdx = indices.single { it.name == "idx_contacts_system_id" }
        assertFalse(sysIdx.unique, "فهرس systemContactId أصبح غير فريد بعد الاعتماد الكامل")
    }

    @Test
    fun sr01_fixturesB_C_D_E_preserveCompositePartialAutoAndPkIndices_andRejectUnsupportedBeforeMutation() = runTest {
        // Fixture B: فهرس فريد مركّب على (systemContactId, accountName)
        val pathB = PlatformFileOps.tempTestDbPath("tmp_test_sr01_fixB_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(pathB, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_sys_account_unique ON contacts(systemContactId, accountName)")
        }
        val beforeB = readSnapshotOf(pathB)
        assertEquals(LegacySchemaLevel.UNKNOWN, LegacySchemaFingerprint.classify(beforeB))
        assertFailsWith<LegacySchemaUnknownException> { LegacyDatabaseAdopter(pathB).adoptIfNeeded() }
        assertEquals(beforeB, readSnapshotOf(pathB), "Fixture B: لا تعديل قبل الرفض")
        BundledSQLiteDriver().open(pathB).use { conn ->
            LegacyDatabaseAdopter(pathB).normalizeContactsIndices(conn)
        }
        val compositeIdx = readContactsIndices(pathB).single { it.name == "idx_contacts_sys_account_unique" }
        assertTrue(compositeIdx.unique)
        assertEquals(listOf("systemContactId", "accountName"), compositeIdx.columns)

        // Fixture C: فهرس فريد جزئي على lookupKey
        val pathC = PlatformFileOps.tempTestDbPath("tmp_test_sr01_fixC_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(pathC, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_lookup_partial ON contacts(lookupKey) WHERE lookupKey IS NOT NULL")
        }
        val beforeC = readSnapshotOf(pathC)
        assertEquals(LegacySchemaLevel.UNKNOWN, LegacySchemaFingerprint.classify(beforeC))
        assertFailsWith<LegacySchemaUnknownException> { LegacyDatabaseAdopter(pathC).adoptIfNeeded() }
        assertEquals(beforeC, readSnapshotOf(pathC), "Fixture C: لا تعديل قبل الرفض")
        BundledSQLiteDriver().open(pathC).use { conn ->
            LegacyDatabaseAdopter(pathC).normalizeContactsIndices(conn)
        }
        val partialIdx = readContactsIndices(pathC).single { it.name == "idx_contacts_lookup_partial" }
        assertTrue(partialIdx.unique)
        assertTrue(partialIdx.partial)
        assertEquals(listOf("lookupKey"), partialIdx.columns)

        // Fixture D: قيد UNIQUE على مستوى الجدول (origin = "u")
        val pathD = PlatformFileOps.tempTestDbPath("tmp_test_sr01_fixD_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(pathD, LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP TABLE contacts")
            conn.executeSQL(
                """
                CREATE TABLE contacts (
                    id INTEGER PRIMARY KEY NOT NULL,
                    systemContactId TEXT NOT NULL,
                    lookupKey TEXT UNIQUE,
                    displayName TEXT NOT NULL,
                    firstName TEXT,
                    lastName TEXT,
                    company TEXT,
                    photoUri TEXT,
                    category TEXT NOT NULL,
                    isFavorite INTEGER NOT NULL DEFAULT 0,
                    excludeFromSmartFilter INTEGER NOT NULL DEFAULT 0,
                    normalizedName TEXT NOT NULL,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            conn.executeSQL("CREATE INDEX idx_contacts_category ON contacts(category)")
            conn.executeSQL("CREATE INDEX idx_contacts_norm_name ON contacts(normalizedName)")
            conn.executeSQL("CREATE INDEX idx_contacts_fav ON contacts(isFavorite)")
        }
        val beforeD = readSnapshotOf(pathD)
        assertEquals(LegacySchemaLevel.UNKNOWN, LegacySchemaFingerprint.classify(beforeD))
        assertFailsWith<LegacySchemaUnknownException> { LegacyDatabaseAdopter(pathD).adoptIfNeeded() }
        assertEquals(beforeD, readSnapshotOf(pathD), "Fixture D: لا تعديل قبل الرفض")
        BundledSQLiteDriver().open(pathD).use { conn ->
            LegacyDatabaseAdopter(pathD).normalizeContactsIndices(conn)
        }
        val autoUniqueIdx = readContactsIndices(pathD).single { it.origin == "u" }
        assertTrue(autoUniqueIdx.unique)
        assertEquals(listOf("lookupKey"), autoUniqueIdx.columns)

        // Fixture E: فهرس مفتاح أساسي تلقائي (origin = "pk")
        val pathE = PlatformFileOps.tempTestDbPath("tmp_test_sr01_fixE_${Random.nextLong(100_000, 999_999)}.db")
        BundledSQLiteDriver().open(pathE).use { conn ->
            conn.executeSQL(
                """
                CREATE TABLE contacts (
                    id TEXT PRIMARY KEY NOT NULL,
                    systemContactId TEXT NOT NULL,
                    category TEXT NOT NULL,
                    normalizedName TEXT NOT NULL,
                    isFavorite INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            LegacyDatabaseAdopter(pathE).normalizeContactsIndices(conn)
        }
        val pkAutoIdx = readContactsIndices(pathE).single { it.origin == "pk" }
        assertTrue(pkAutoIdx.unique)
        assertEquals(listOf("id"), pkAutoIdx.columns)
    }

    // ===== 10) SR-02: مصفوفة تصنيف المخطط الصارمة (Cases A–L + حالات Metadata/Index) وإثبات عدم التعديل =====

    private suspend fun assertUnknownAndZeroMutation(
        label: String,
        baseLevel: LegacyFixtureLevel,
        mutator: suspend (androidx.sqlite.SQLiteConnection) -> Unit,
    ) {
        val path = PlatformFileOps.tempTestDbPath("tmp_test_sr02_${label}_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(path, baseLevel) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'c_$label', 'اختبار $label', 'WORK', 0, 'اختبار', 1, 2)",
            )
            mutator(conn)
        }
        val snapshotBefore = readSnapshotOf(path)
        val rowsBefore = contactsRowCount(path)

        val classified = LegacySchemaFingerprint.classify(snapshotBefore)
        assertEquals(LegacySchemaLevel.UNKNOWN, classified, "Case $label يجب أن يُصنَّف UNKNOWN")

        val snapshotAfterClassify = readSnapshotOf(path)
        assertEquals(snapshotBefore, snapshotAfterClassify, "Case $label: classify قراءة فقط بلا أي تعديل")

        assertFailsWith<LegacySchemaUnknownException>("Case $label: المُعتمِد يرفض تشغيل الشيم") {
            LegacyDatabaseAdopter(path).adoptIfNeeded()
        }

        val snapshotAfterAdoptAttempt = readSnapshotOf(path)
        assertEquals(snapshotBefore, snapshotAfterAdoptAttempt, "Case $label: لا تعديل للمخطط عند رفض UNKNOWN")
        assertEquals(rowsBefore, contactsRowCount(path), "Case $label: لا تعديل للبيانات عند رفض UNKNOWN")
    }

    @Test
    fun sr02_classificationMatrix_casesAtoL_andStrictMetadataAndIndexValidation_zeroMutationOnUnknown() = runTest {
        // Case A: Exact L0 -> L0
        val pathA = PlatformFileOps.tempTestDbPath("tmp_test_sr02_caseA_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(pathA, LegacyFixtureLevel.L0)
        val snapA = readSnapshotOf(pathA)
        assertEquals(LegacySchemaLevel.L0, LegacySchemaFingerprint.classify(snapA))
        assertEquals(snapA, readSnapshotOf(pathA))

        // Case B: Exact L1 -> L1
        val pathB = PlatformFileOps.tempTestDbPath("tmp_test_sr02_caseB_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(pathB, LegacyFixtureLevel.L1)
        val snapB = readSnapshotOf(pathB)
        assertEquals(LegacySchemaLevel.L1, LegacySchemaFingerprint.classify(snapB))
        assertEquals(snapB, readSnapshotOf(pathB))

        // Case C1: FTS (5-col) without checkpoints -> UNKNOWN
        assertUnknownAndZeroMutation("C1_fts5_no_checkpoints", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP TABLE contacts_sync_checkpoints")
        }

        // Case C2: FTS (6-col) without checkpoints -> UNKNOWN
        assertUnknownAndZeroMutation("C2_fts6_no_checkpoints", LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL("DROP TABLE contacts_sync_checkpoints")
        }

        // Case D: Checkpoints without FTS -> UNKNOWN
        assertUnknownAndZeroMutation("D_checkpoints_no_fts", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP TABLE contacts_fts")
        }

        // Case E: Missing required table (services) -> UNKNOWN
        assertUnknownAndZeroMutation("E_missing_services", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP TABLE services")
        }

        // Case F: Invalid checkpoint columns (nullable / missing PK / wrong affinity) -> UNKNOWN
        assertUnknownAndZeroMutation("F_invalid_checkpoints", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP TABLE contacts_sync_checkpoints")
            conn.executeSQL("CREATE TABLE contacts_sync_checkpoints (key TEXT, value TEXT)")
        }

        // Case G: Missing invariant table (arham) -> UNKNOWN
        assertUnknownAndZeroMutation("G_missing_arham", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP TABLE arham")
        }

        // Case H: Unexpected contacts column -> UNKNOWN
        assertUnknownAndZeroMutation("H_unexpected_contacts_col", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("ALTER TABLE contacts ADD COLUMN rogue_column TEXT")
        }

        // Case I: Missing systemDeletedAt from L1 -> UNKNOWN
        assertUnknownAndZeroMutation("I_missing_systemDeletedAt", LegacyFixtureLevel.L0) { conn ->
            listOf(
                "lookupKey" to "TEXT",
                "accountType" to "TEXT",
                "accountName" to "TEXT",
                "fileAs" to "TEXT NOT NULL DEFAULT ''",
                "prefix" to "TEXT",
                "middleName" to "TEXT",
                "suffix" to "TEXT",
                "nickname" to "TEXT",
                "phoneticName" to "TEXT",
                "department" to "TEXT",
                "notes" to "TEXT",
                "customRingtoneUri" to "TEXT",
                "sendToVoicemail" to "INTEGER NOT NULL DEFAULT 0",
                "isBlocked" to "INTEGER NOT NULL DEFAULT 0",
                "linkedServiceCloudId" to "TEXT",
                // systemDeletedAt omitted intentionally
            ).forEach { (name, ddl) ->
                conn.executeSQL("ALTER TABLE contacts ADD COLUMN $name $ddl")
            }
            conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contacts_system_id ON contacts(systemContactId)")
            conn.executeSQL("CREATE TABLE contacts_sync_checkpoints (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            conn.executeSQL("CREATE VIRTUAL TABLE contacts_fts USING fts4(displayName, nickname, company, notes, normalizedName)")
        }

        // Case J1: Unexpected unique contacts index -> UNKNOWN
        assertUnknownAndZeroMutation("J1_unexpected_unique_contacts_idx", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("CREATE UNIQUE INDEX idx_contacts_lookup_unique ON contacts(lookupKey)")
        }

        // Case J2: Missing required services index -> UNKNOWN
        assertUnknownAndZeroMutation("J2_missing_services_idx", LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL("DROP INDEX idx_services_phone")
        }

        // Case K: Partial contacts column subset (15 columns) -> UNKNOWN
        assertUnknownAndZeroMutation("K_partial_15_cols", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("ALTER TABLE contacts ADD COLUMN lookupKey TEXT")
            conn.executeSQL("ALTER TABLE contacts ADD COLUMN accountType TEXT")
        }

        // Case L: Unknown rogue table -> UNKNOWN
        assertUnknownAndZeroMutation("L_rogue_table", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("CREATE TABLE unknown_rogue_table (id INTEGER PRIMARY KEY)")
        }

        // Additional metadata & index checks (§4.8):
        // 1) Incorrect SQLite affinity
        assertUnknownAndZeroMutation("Meta_wrong_affinity", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP TABLE dispute_logs")
            conn.executeSQL(
                """
                CREATE TABLE dispute_logs (
                    id INTEGER PRIMARY KEY NOT NULL,
                    serviceCloudId TEXT NOT NULL,
                    description TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    resolvedAt INTEGER
                )
                """.trimIndent(),
            )
        }

        // 2) Incorrect NOT NULL
        assertUnknownAndZeroMutation("Meta_wrong_notNull", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP TABLE call_events")
            conn.executeSQL(
                """
                CREATE TABLE call_events (
                    callLogId TEXT PRIMARY KEY NOT NULL,
                    e164 TEXT,
                    direction TEXT NOT NULL,
                    durationSec INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    source TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.executeSQL("CREATE INDEX idx_call_events_ts ON call_events(timestamp)")
        }

        // 3) Incorrect primary-key position
        assertUnknownAndZeroMutation("Meta_wrong_pkPosition", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP TABLE arham")
            conn.executeSQL(
                """
                CREATE TABLE arham (
                    contactId INTEGER NOT NULL,
                    relation TEXT NOT NULL,
                    layer TEXT NOT NULL,
                    parentContactId INTEGER,
                    frequency TEXT NOT NULL,
                    lastContactedAt INTEGER,
                    pausedUntil INTEGER,
                    createdAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        // 4) Incorrect defaults
        assertUnknownAndZeroMutation("Meta_wrong_default", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP TABLE contact_phones")
            conn.executeSQL(
                """
                CREATE TABLE contact_phones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    contactId INTEGER NOT NULL,
                    raw TEXT NOT NULL,
                    e164 TEXT NOT NULL,
                    type TEXT NOT NULL DEFAULT 'HOME',
                    isPrimary INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            conn.executeSQL("CREATE INDEX idx_contact_phones_cid ON contact_phones(contactId)")
            conn.executeSQL("CREATE INDEX idx_contact_phones_e164 ON contact_phones(e164)")
        }

        // 5) Unexpected partial index
        assertUnknownAndZeroMutation("Idx_unexpected_partial", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP INDEX idx_services_phone")
            conn.executeSQL("CREATE INDEX idx_services_phone ON services(phoneE164) WHERE isHot = 1")
        }

        // 6) Expression index with NULL column metadata (cid == -2)
        assertUnknownAndZeroMutation("Idx_expression_null_col", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("CREATE INDEX idx_contacts_expr ON contacts(LOWER(displayName))")
        }

        // 7) DESC index where ASC is required
        assertUnknownAndZeroMutation("Idx_desc_order", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("DROP INDEX idx_call_events_ts")
            conn.executeSQL("CREATE INDEX idx_call_events_ts ON call_events(timestamp DESC)")
        }

        // 8) B-01: Non-unique systemContactId index with non-standard name -> UNKNOWN & zero mutation
        assertUnknownAndZeroMutation("Idx_non_standard_systemContactId_name", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("CREATE INDEX idx_rogue_sys ON contacts(systemContactId)")
        }

        // 9) NB-02: Rogue table with contacts_fts_ prefix -> UNKNOWN & zero mutation
        assertUnknownAndZeroMutation("Table_rogue_fts_shadow_prefix", LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL("CREATE TABLE contacts_fts_rogue (id INTEGER PRIMARY KEY)")
        }
    }

    // ===== 11) SR-04: التحقق من القاعدة الحيّة، قراءة كل الجداول عند خلو contacts، واستعادة الانقطاعات Crash A–D =====

    @Test
    fun sr04_emptyContactsTable_stillVerifiesContactPhonesArhamServicesAndFts_beforeBackupDeletion() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_empty_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        // قاعدة L1 بلا أي جهات نشطة (صف واحد محذوف ناعماً tombstone) مع وجود صفوف في contact_phones وarham وservices:
        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs, systemDeletedAt) " +
                    "VALUES (1, 'tomb_only', 'محذوف ناعماً', 'WORK', 0, 'محذوف ناعما', 1, 2, '', 999)",
            )
            conn.executeSQL(
                "INSERT INTO contact_phones (contactId, raw, e164, type, isPrimary) VALUES (1, '01012345678', '+201012345678', 'MOBILE', 1)",
            )
            conn.executeSQL(
                "INSERT INTO arham (contactId, relation, layer, frequency, createdAt) " +
                    "VALUES (1, '${Relation.MOTHER.name}', '${Relation.MOTHER.layer.name}', '${ArhamFrequency.WEEKLY.name}', 100)",
            )
            conn.executeSQL(
                "INSERT INTO services (cloudId, name, professionId, professionNameAr, professionNameEn, phoneE164, visibility, trustScore, uniqueCallers, retentionRate, ratingAvg, ratingCount) " +
                    "VALUES ('srv_1', 'سباك', 'p1', 'سباكة', 'Plumbing', '+201099990000', '${ServiceVisibility.VERIFIED.name}', 0.9, 5, 0.8, 4.5, 10)",
            )
        }

        // لو فشل قراءة arham حتى مع أن contacts النشطة فارغة، يجب أن يفشل الفتح وتبقى النسخة الواقية:
        assertFailsWith<IllegalStateException> {
            LocalSilaDatabase(
                dbFilePath = tempDbPath,
                dispatchers = testDispatchers,
                seedDemoData = false,
                adoptionStageHook = { stage ->
                    if (stage == AdoptionVerificationStage.ARHAM_READ) {
                        error("injected-arham-read-failure-on-empty-active-contacts")
                    }
                },
                backupDeleteOverride = null,
            )
        }
        assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تبقى عند فشل قراءة arham حتى لو كانت الجهات النشطة فارغة")

        // عند الفتح السليم، تمر جميع مراحل القراءة والتحقق بالترتيب وتُحذف النسخة الواقية:
        val recordedStages = mutableListOf<AdoptionVerificationStage>()
        val db = LocalSilaDatabase(
            dbFilePath = tempDbPath,
            dispatchers = testDispatchers,
            seedDemoData = false,
            adoptionStageHook = { stage -> recordedStages += stage },
            backupDeleteOverride = null,
        )
        try {
            assertTrue(db.observeContacts().first().isEmpty())
            assertTrue(recordedStages.contains(AdoptionVerificationStage.CONTACTS_READ))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.CONTACT_PHONES_READ))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.ARHAM_READ))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.SERVICES_READ))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.VERSION_CHECK))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.INTEGRITY_CHECK))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.FTS_CHECK))
            assertTrue(recordedStages.contains(AdoptionVerificationStage.BACKUP_DELETE))
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تُحذف بعد اكتمال جميع الفحوص")
        } finally {
            db.closeConnection()
        }
    }

    @Test
    fun sr04_crashA_shimCommitsBeforeRoomOpens_nextStartupVerifiesBackupAndCompletesAdoption_orFailsIfBackupMissing() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_crashA_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L0) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt) " +
                    "VALUES (1, 'crash_a', 'استئناف بعد الشيم', 'WORK', 0, 'استئناف بعد الشيم', 1, 2)",
            )
        }

        // محاكاة Crash A: الشيم يلتزم بنجاح (PARITY_DONE, user_version = 0) والعملية تتوقف قبل فتح Room:
        val firstRun = LegacyDatabaseAdopter(tempDbPath).adoptIfNeeded()
        assertEquals(LegacySchemaLevel.L0, firstRun.detectedLevel)
        assertEquals(backupPath, firstRun.backupPath)
        assertEquals(0, userVersionOf(tempDbPath), "Room لم يفتح القاعدة بعد")
        assertEquals(LegacySchemaLevel.PARITY_DONE, LegacySchemaFingerprint.classify(readSnapshotOf(tempDbPath)))
        assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية موجودة بعد التزام الشيم")

        // التشغيل التالي يكتشف PARITY_DONE والنسخة الواقية القائمة، فيتحقق ويفتح Room ويحذف النسخة:
        val db = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, db.observeContacts().first().size)
            assertEquals(SILA_ROOM_VERSION, userVersionOf(tempDbPath))
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية حُذفت بعد اكتمال الاستئناف")
        } finally {
            db.closeConnection()
        }

        // إذا كانت القاعدة في حالة PARITY_DONE ولكن النسخة الواقية مفقودة أو فارغة/تالفة، يفشل الاعتماد مغلقاً ولا يدّعي النجاح:
        val missingBackupDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_crashA_nobackup_${Random.nextLong(100_000, 999_999)}.db")
        LegacySchemaFixtures.createLegacyDatabase(missingBackupDbPath, LegacyFixtureLevel.L0)
        LegacyDatabaseAdopter(missingBackupDbPath).adoptIfNeeded()
        SilaDatabaseBackup.deleteBackupIfExists(missingBackupDbPath)
        assertFailsWith<LegacyAdoptionException>("PARITY_DONE بلا نسخة واقية يجب أن يرمي LegacyAdoptionException") {
            LegacyDatabaseAdopter(missingBackupDbPath).adoptIfNeeded()
        }
        // NB-01: ملف نسخة واقية فارغ (بلا جدول contacts) يُرفض في PARITY_DONE:
        BundledSQLiteDriver().open(SilaDatabaseBackup.backupPathFor(missingBackupDbPath)).use { conn ->
            conn.executeSQL("CREATE TABLE dummy_empty (id INTEGER PRIMARY KEY)")
        }
        assertFalse(SilaDatabaseBackup.verifiedBackupExists(missingBackupDbPath))
        assertFailsWith<LegacyAdoptionException>("PARITY_DONE بنسخة واقية غير صالحة يجب أن يرمي LegacyAdoptionException") {
            LegacyDatabaseAdopter(missingBackupDbPath).adoptIfNeeded()
        }
    }

    @Test
    fun sr04_crashB_roomStampsSchemaVersion_processStopsBeforeBackupDeletion_nextStartupCleansUpAfterVerification() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_crashB_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'crash_b', 'بعد ختم الإصدار', 'WORK', 0, 'بعد ختم الاصدار', 1, 2, '')",
            )
        }

        // محاكاة Crash B: Room يفتح ويختم إصداره ولكن العملية تتوقف قبل حذف النسخة الواقية:
        assertFailsWith<IllegalStateException> {
            LocalSilaDatabase(
                dbFilePath = tempDbPath,
                dispatchers = testDispatchers,
                seedDemoData = false,
                adoptionStageHook = { stage ->
                    if (stage == AdoptionVerificationStage.BEFORE_BACKUP_DELETE) {
                        error("simulated-process-stop-before-backup-delete")
                    }
                },
                backupDeleteOverride = null,
            )
        }

        assertEquals(SILA_ROOM_VERSION, userVersionOf(tempDbPath), "Room ختم إصداره بالفعل")
        assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية لم تُحذف بسبب التوقف")

        // التشغيل التالي (ROOM_MANAGED) يكتشف النسخة الواقية المتبقية، يتحقق من القاعدة الحيّة، ثم يحذفها:
        val recovered = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, recovered.observeContacts().first().size)
            assertFalse(PlatformFileOps.fileExists(backupPath), "التشغيل التالي حذف النسخة الواقية بعد التحقق")
            assertFalse(recovered.hasPendingBackupDeletion)
        } finally {
            recovered.closeConnection()
        }
    }

    @Test
    fun sr04_crashC_andAllVerificationFailureStages_preserveBackup_andAllowCleanRecovery() = runTest {
        val failingStages = listOf(
            AdoptionVerificationStage.ROOM_OPEN,
            AdoptionVerificationStage.CONTACTS_READ,
            AdoptionVerificationStage.CONTACT_PHONES_READ,
            AdoptionVerificationStage.ARHAM_READ,
            AdoptionVerificationStage.SERVICES_READ,
            AdoptionVerificationStage.VERSION_CHECK,
            AdoptionVerificationStage.INTEGRITY_CHECK,
            AdoptionVerificationStage.FTS_CHECK,
        )

        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_crashC_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L2_OR_L3) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'crash_c', 'تحقق الفشل', 'FAMILY', 1, 'تحقق الفشل', 1, 2, '')",
            )
        }

        for (targetStage in failingStages) {
            assertFailsWith<IllegalStateException>("الفشل في المرحلة $targetStage يجب أن يوقف التهيئة") {
                LocalSilaDatabase(
                    dbFilePath = tempDbPath,
                    dispatchers = testDispatchers,
                    seedDemoData = false,
                    adoptionStageHook = { stage ->
                        if (stage == targetStage) error("injected-failure-at-$targetStage")
                    },
                    backupDeleteOverride = null,
                )
            }
            assertTrue(
                PlatformFileOps.fileExists(backupPath),
                "النسخة الواقية يجب أن تبقى محفوظة عند فشل المرحلة $targetStage",
            )
        }

        // اختبار إلغاء الكوروتين (CancellationException):
        assertFailsWith<CancellationException> {
            LocalSilaDatabase(
                dbFilePath = tempDbPath,
                dispatchers = testDispatchers,
                seedDemoData = false,
                adoptionStageHook = { stage ->
                    if (stage == AdoptionVerificationStage.SERVICES_READ) {
                        throw CancellationException("simulated-coroutine-cancellation")
                    }
                },
                backupDeleteOverride = null,
            )
        }
        assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تبقى محفوظة عند إلغاء الكوروتين")

        // الاستئناف السليم بعد كل الإخفاقات يحذف النسخة الواقية بنجاح:
        val recovered = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, recovered.observeContacts().first().size)
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية تُحذف بعد نجاح جميع الفحوص")
        } finally {
            recovered.closeConnection()
        }
    }

    @Test
    fun sr04_crashD_backupDeletionFailure_doesNotCorruptDatabase_retainsBackup_andRetriesOnNextStartup() = runTest {
        val tempDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_crashD_${Random.nextLong(100_000, 999_999)}.db")
        val backupPath = SilaDatabaseBackup.backupPathFor(tempDbPath)

        LegacySchemaFixtures.createLegacyDatabase(tempDbPath, LegacyFixtureLevel.L1) { conn ->
            conn.executeSQL(
                "INSERT INTO contacts (id, systemContactId, displayName, category, isFavorite, normalizedName, createdAt, updatedAt, fileAs) " +
                    "VALUES (1, 'crash_d', 'فشل حذف النسخة', 'WORK', 0, 'فشل حذف النسخه', 1, 2, '')",
            )
        }

        // المحاولة الأولى: فشل حذف النسخة الواقية (يعيد false):
        val dbFirst = LocalSilaDatabase(
            dbFilePath = tempDbPath,
            dispatchers = testDispatchers,
            seedDemoData = false,
            adoptionStageHook = null,
            backupDeleteOverride = { false },
        )
        try {
            assertEquals(1, dbFirst.observeContacts().first().size, "القاعدة الحيّة سليمة وتعمل بشكل طبيعي")
            assertTrue(dbFirst.hasPendingBackupDeletion, "حالة الحذف المعلّق مسجلة لإعادة المحاولة")
            assertTrue(PlatformFileOps.fileExists(backupPath), "النسخة الواقية باقية على القرص بعد فشل الحذف")
        } finally {
            dbFirst.closeConnection()
        }

        // التشغيل التالي ينجح في حذف النسخة الواقية المتبقية بعد التحقق من القاعدة الحيّة:
        val dbSecond = LocalSilaDatabase(dbFilePath = tempDbPath, dispatchers = testDispatchers)
        try {
            assertEquals(1, dbSecond.observeContacts().first().size)
            assertFalse(dbSecond.hasPendingBackupDeletion)
            assertFalse(PlatformFileOps.fileExists(backupPath), "النسخة الواقية حُذفت في التشغيل التالي")
        } finally {
            dbSecond.closeConnection()
        }

        // اختبار فشل التحقق الفعلي لمخطط Room (Schema validation failure) مع وجود نسخة واقية:
        val invalidSchemaDbPath = PlatformFileOps.tempTestDbPath("tmp_test_sr04_schemaval_${Random.nextLong(100_000, 999_999)}.db")
        val invalidSchemaBackupPath = SilaDatabaseBackup.backupPathFor(invalidSchemaDbPath)
        LegacySchemaFixtures.createLegacyDatabase(invalidSchemaDbPath, LegacyFixtureLevel.L2_OR_L3)
        // إنشاء نسخة واقية موثقة ثم كسر مخطط جدول مُدار مع وضع user_version = 1 لمحاكاة فشل تحقق Room:
        SilaDatabaseBackup.createVerifiedBackup(invalidSchemaDbPath)
        BundledSQLiteDriver().open(invalidSchemaDbPath).use { conn ->
            conn.executeSQL("DROP TABLE dispute_logs")
            conn.executeSQL("CREATE TABLE dispute_logs (id INTEGER PRIMARY KEY NOT NULL)")
            conn.executeSQL("PRAGMA user_version = 1")
        }
        assertFailsWith<Throwable>("فشل التحقق الفعلي لمخطط Room يجب أن يرمي استثناءً") {
            LocalSilaDatabase(dbFilePath = invalidSchemaDbPath, dispatchers = testDispatchers)
        }
        assertTrue(
            PlatformFileOps.fileExists(invalidSchemaBackupPath),
            "النسخة الواقية تبقى محفوظة عند فشل تحقق مخطط Room",
        )
    }
}
