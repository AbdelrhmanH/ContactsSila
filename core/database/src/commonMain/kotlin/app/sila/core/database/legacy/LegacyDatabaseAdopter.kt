package app.sila.core.database.legacy

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.open
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.common.ArabicTextNormalizer

/** فشل آمن: مخطط قاعدة غير مصنَّف — لا يُعدَّل ولا يُفتح (فحص الدخول قبل أي شيء). */
internal class LegacySchemaUnknownException(safeSummary: String) :
    RuntimeException("PR_B adoption: unknown legacy schema (fail-closed) — snapshot: $safeSummary")

/** فشل تنفيذ شيم الاعتماد — المعاملة تُرجَع بالكامل والقاعدة تبقى كما كانت. */
internal class LegacyAdoptionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** نتيجة الاعتماد: المستوى قبل الشيم + معلومات النسخة الواقية. */
internal data class LegacyAdoptionResult(
    /** المستوى المكتشف قبل الاعتماد (FRESH/ROOM_MANAGED/PARITY_DONE تعني «لا شيم»). */
    val detectedLevel: LegacySchemaLevel,
    /** مسار النسخة الواقية — `null` حين لا شيم (لا حماية مطلوبة). */
    val backupPath: String?,
    /** هل أُنشئت النسخة في هذا الاعتماد (بدل الاكتفاء بنسخة سابقة موثقة)؟ */
    val backupCreated: Boolean,
)

/**
 * مُعتمِد المخطط القديم إلى مخطط Room (PR B — ADR-0011 §الاعتماد الآمن).
 *
 * مسار واحد صارم: بصمة بنية (قراءة فقط) → تصنيف صارم → نسخة واقية موثقة → شيم ضيق
 * داخل معاملة واحدة (`BEGIN IMMEDIATE`) → إعادة بصمة والتحقق من الوصول إلى الحالة الهدف.
 *
 * قواعد لا تُكسر:
 * - **لا `Migration(0,1)`** — الاعتماد عبر «إنشاء/تحقق إصدار-صفر» في Room: الكيانات تطابق
 *   الحقيقة الفيزيائية بعد الشيم، وRoom يتحقق ويُسجل `user_version = 1` عند أول فتح.
 * - **فشل آمن**: مخطط غير مصنَّف (مشغّلات/FKs/جداول غير معروفة/أعمدة خارج الهدف) ⇒ نسخة
 *   احتياطية ثم رمي [LegacySchemaUnknownException] — **لا تعديل إطلاقاً**.
 * - **لا دمج ولا إزالة تكرار**: كل سجلات المستخدم تُحفظ كما هي (Decision 2 — فهرس
 *   `systemContactId` غير فريد).
 * - الشيم **idempotent**: يستهدف فجوات الحالة (أعمدة غائبة/PK بلا NOT NULL/FTS بأعمدة
 *   ناقصة) — فحالات الانقطاع الجزئي تُستأنف بأمان.
 */
internal class LegacyDatabaseAdopter(
    private val dbFilePath: String,
    private val driver: BundledSQLiteDriver = BundledSQLiteDriver(),
) {

    /** عمود التكافؤ التي أضافتها ترقية v1→v2 التاريخية (Issue #23) بترتيبها الأصلي. */
    private val parityColumns = listOf(
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
        "systemDeletedAt" to "INTEGER",
    )

    private val targetContactIndexDdl = listOf(
        "idx_contacts_category" to "CREATE INDEX IF NOT EXISTS idx_contacts_category ON contacts(category)",
        "idx_contacts_norm_name" to "CREATE INDEX IF NOT EXISTS idx_contacts_norm_name ON contacts(normalizedName)",
        "idx_contacts_fav" to "CREATE INDEX IF NOT EXISTS idx_contacts_fav ON contacts(isFavorite)",
        "idx_contacts_system_id" to "CREATE INDEX IF NOT EXISTS idx_contacts_system_id ON contacts(systemContactId)",
    )

    private val targetContactIndexNames = targetContactIndexDdl.map { it.first }.toSet()

    private val ftsCreateSql = LegacyFtsDdl.CREATE_CONTACTS_FTS

    /**
     * خطاف حقن فشل داخل معاملة الشيم لاختبار التراجع الذرّي — يُستدعى بعد اكتمال كل
     * خطوات الشيم وقبل `COMMIT` حصراً؛ الاستثناء الرمي يُرجِع المعاملة كاملة ويُغلَّف
     * بـ [LegacyAdoptionException] (نفس مبدأ خطافات الواجهة الخارجية).
     */
    internal var shimFaultHook: (() -> Throwable)? = null

    /**
     * يفحص القاعدة ويُعتمِدها عند الحاجة فقط. يُستدعى **قبل** فتح Room (داخل تهيئة الواجهة)
     * — على اتصال مستقل قراءة/كتابة، فلا تضارب مع Room.
     */
    suspend fun adoptIfNeeded(): LegacyAdoptionResult {
        if (!PlatformFileOps.fileExists(dbFilePath)) {
            return LegacyAdoptionResult(LegacySchemaLevel.FRESH, backupPath = null, backupCreated = false)
        }

        val snapshot = readSnapshot()
        val level = LegacySchemaFingerprint.classify(snapshot)

        return when (level) {
            // لا شيء تتبناه: Room يديرها أصلاً / تثبيت جديد / حالة هدف جاهزة.
            LegacySchemaLevel.ROOM_MANAGED,
            LegacySchemaLevel.FRESH,
            LegacySchemaLevel.PARITY_DONE,
            -> LegacyAdoptionResult(level, backupPath = null, backupCreated = false)

            // غير مصنَّف ⇒ فشل آمن: نسخة واقية (كأفضل جهد حفظ تشخيصي) ثم رمي — لا تعديل.
            LegacySchemaLevel.UNKNOWN -> {
                runCatching {
                    if (!SilaDatabaseBackup.verifiedBackupExists(dbFilePath)) {
                        SilaDatabaseBackup.createVerifiedBackup(dbFilePath)
                    }
                }
                throw LegacySchemaUnknownException(snapshot.safeSummary())
            }

            // المستويات التاريخية المدعومة — اعتماد موثق.
            LegacySchemaLevel.L0,
            LegacySchemaLevel.L1,
            LegacySchemaLevel.L2_OR_L3,
            -> adoptKnownLevel(level, snapshot)
        }
    }

    /** يقرأ بصمة المخطط من ملف القاعدة عبر اتصال مستقل (قراءة فقط — لا تعديل). */
    private suspend fun readSnapshot(): LegacySchemaSnapshot =
        driver.open(dbFilePath).use { conn -> LegacySchemaFingerprint.readSnapshot(conn) }

    private suspend fun adoptKnownLevel(
        level: LegacySchemaLevel,
        @Suppress("UNUSED_PARAMETER") snapshot: LegacySchemaSnapshot,
    ): LegacyAdoptionResult {
        val backupPath = SilaDatabaseBackup.backupPathFor(dbFilePath)
        val backupCreated = if (SilaDatabaseBackup.verifiedBackupExists(dbFilePath)) {
            // نسخة سابقة موثقة موجودة (اعتماد مُقاطَع سابقاً) — تكفي كحماية، لا تُعاد.
            false
        } else {
            SilaDatabaseBackup.createVerifiedBackup(dbFilePath)
            true
        }

        runShimTransaction()

        // تحقق ما بعد الشيم: الوصول للحالة الهدف شرط للنجاح — وإلا فشل آمن (النسخة باقية).
        val after = readSnapshot()
        if (LegacySchemaFingerprint.classify(after) != LegacySchemaLevel.PARITY_DONE) {
            throw LegacyAdoptionException(
                "PR_B adoption: shim completed but target state not reached — " +
                    "level=$level afterState=${after.safeSummary()}",
            )
        }
        return LegacyAdoptionResult(
            detectedLevel = level,
            backupPath = backupPath,
            backupCreated = backupCreated,
        )
    }

    /** الشيم كله داخل معاملة واحدة — أي فشل يُرجع القاعدة لحالتها السابقة بالكامل. */
    private suspend fun runShimTransaction() {
        val conn = driver.open(dbFilePath)
        try {
            conn.executeSQL("BEGIN IMMEDIATE")
            try {
                ensureContactParityColumns(conn)
                rebuildContactPhonesWithNotNullPk(conn)
                rebuildFtsIfNeeded(conn)
                ensureCheckpointsTable(conn)
                normalizeContactsIndices(conn)
                ensurePhonesIndices(conn)
                backfillFtsFromContacts(conn)
                shimFaultHook?.invoke()?.let { throw it }
                conn.executeSQL("COMMIT")
            } catch (t: Throwable) {
                runCatching { conn.executeSQL("ROLLBACK") }
                throw LegacyAdoptionException("PR_B adoption: shim failed — rolled back", t)
            }
        } finally {
            conn.close()
        }
    }

    /** أعمدة تكافؤ الكيان + tombstone — بنفس DDL التاريخي حرفياً (Issue #23). */
    private suspend fun ensureContactParityColumns(conn: SQLiteConnection) {
        val existing = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(contacts)").use { stmt ->
            while (stmt.step()) existing += stmt.getText(1)
        }
        parityColumns.forEach { (name, ddl) ->
            if (name !in existing) conn.executeSQL("ALTER TABLE contacts ADD COLUMN $name $ddl")
        }
    }

    /**
     * `contact_phones` التاريخية تُعلن PK بلا NOT NULL (notnull=0) بينما Room يتوقع
     * `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL` — إعادة بناء وحيدة تحفظ البيانات
     * (نسخ أعمدة ثم إسقاط القديم). تُنفَّذ فقط عند عدم إعلان NOT NULL (idempotent).
     */
    private suspend fun rebuildContactPhonesWithNotNullPk(conn: SQLiteConnection) {
        val pkDeclaresNotNull = conn.prepare("PRAGMA table_info(contact_phones)").use { stmt ->
            var declares = true
            while (stmt.step()) {
                if (stmt.getLong(5).toInt() > 0 && stmt.getLong(3) == 0L) declares = false
            }
            declares
        }
        if (pkDeclaresNotNull) return

        conn.executeSQL("ALTER TABLE contact_phones RENAME TO contact_phones_legacy_shim")
        conn.executeSQL(
            """
            CREATE TABLE contact_phones (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                contactId INTEGER NOT NULL,
                raw TEXT NOT NULL,
                e164 TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'MOBILE',
                isPrimary INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        conn.executeSQL(
            """
            INSERT INTO contact_phones (id, contactId, raw, e164, type, isPrimary)
            SELECT id, contactId, raw, e164, type, isPrimary FROM contact_phones_legacy_shim
            """.trimIndent(),
        )
        conn.executeSQL("DROP TABLE contact_phones_legacy_shim")
        // فهارس الجدول المُعاد تسميته سقطت مع DROP — تُعاد إنشاؤها في ensurePhonesIndices.
    }

    /**
     * جداول FTS4 لا تدعم ALTER — الإحلال الآمن الوحيد: إعادة إنشاء ثم إعادة تعبئة كاملة من contacts.
     * إعادة البناء عند اختلاف الأعمدة **أو اختلاف خيارات التعريف** (`tokenize=`/`content=`) عن
     * الثابت الموحد [LegacyFtsDdl.TARGET_OPTIONS] — الفتيلات الواقية: FTS مشتق بالكامل من
     * contacts فلا فقدان بيانات (تحصين مراجعة GLM: خيارات أجنبية بأعمدة مطابقة لا تعبر البوابة).
     */
    private suspend fun rebuildFtsIfNeeded(conn: SQLiteConnection) {
        val columns = conn.prepare("PRAGMA table_info(contacts_fts)").use { stmt ->
            val names = mutableListOf<String>()
            while (stmt.step()) names += stmt.getText(1)
            names
        }
        val liveOptions = conn.prepare("SELECT sql FROM sqlite_master WHERE name = 'contacts_fts'").use { stmt ->
            if (stmt.step() && !stmt.isNull(0)) LegacyFtsDdl.optionsOf(stmt.getText(0)) else emptySet()
        }
        if (columns == FTS_TARGET_COLUMNS && liveOptions == LegacyFtsDdl.TARGET_OPTIONS) return

        conn.executeSQL("DROP TABLE IF EXISTS contacts_fts")
        conn.executeSQL(ftsCreateSql)
    }

    private suspend fun ensureCheckpointsTable(conn: SQLiteConnection) {
        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts_sync_checkpoints (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    /** فهارس contacts: إنشاء الناقصة + إسقاط أي فهرس إضافي غير مستهدف (مطابقة TableInfo). */
    private suspend fun normalizeContactsIndices(conn: SQLiteConnection) {
        val extraIndices = conn.prepare("PRAGMA index_list(contacts)").use { stmt ->
            val names = mutableListOf<String>()
            while (stmt.step()) {
                val indexName = stmt.getText(1)
                val isUnique = stmt.getLong(2) != 0L
                if (!indexName.startsWith("sqlite_autoindex") && (indexName !in targetContactIndexNames || isUnique)) {
                    names += indexName
                }
            }
            names
        }
        extraIndices.forEach { conn.executeSQL("DROP INDEX IF EXISTS \"$it\"") }
        targetContactIndexDdl.forEach { (_, ddl) -> conn.executeSQL(ddl) }
    }

    private suspend fun ensurePhonesIndices(conn: SQLiteConnection) {
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_cid ON contact_phones(contactId)")
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_e164 ON contact_phones(e164)")
    }

    /**
     * إعادة تعبئة FTS idempotent — نص المعيار التاريخي حرفياً (يعيد ترميم docids المفهرسة
     * سابقاً، ويطبق ArabicTextNormalizer — SQL لا يستطيع). يُنفَّذ بعد إضافة أعمدة التكافؤ
     * لذا فلتر `systemDeletedAt IS NULL` صحيح في كل المستويات.
     */
    private suspend fun backfillFtsFromContacts(conn: SQLiteConnection) {
        val alreadyIndexed = mutableSetOf<Long>()
        conn.prepare("SELECT docid FROM contacts_fts").use { stmt ->
            while (stmt.step()) alreadyIndexed += stmt.getLong(0)
        }
        conn.prepare(
            """
            SELECT id, displayName, nickname, company, department, notes, normalizedName
            FROM contacts WHERE systemDeletedAt IS NULL
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                val contactId = stmt.getLong(0)
                if (contactId in alreadyIndexed) continue
                conn.prepare(
                    "INSERT INTO contacts_fts (docid, displayName, nickname, company, department, notes, normalizedName) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { insert ->
                    insert.bindLong(1, contactId)
                    insert.bindText(2, ArabicTextNormalizer.normalize(stmt.getText(1)))
                    if (stmt.isNull(2)) insert.bindNull(3) else insert.bindText(3, ArabicTextNormalizer.normalize(stmt.getText(2)))
                    if (stmt.isNull(3)) insert.bindNull(4) else insert.bindText(4, ArabicTextNormalizer.normalize(stmt.getText(3)))
                    if (stmt.isNull(4)) insert.bindNull(5) else insert.bindText(5, ArabicTextNormalizer.normalize(stmt.getText(4)))
                    if (stmt.isNull(5)) insert.bindNull(6) else insert.bindText(6, ArabicTextNormalizer.normalize(stmt.getText(5)))
                    insert.bindText(7, ArabicTextNormalizer.normalize(stmt.getText(6)))
                    insert.step()
                }
            }
        }
    }

    private companion object {
        /** ترتيب أعمدة FTS الهدف (بعد T3 — Issue #24). */
        val FTS_TARGET_COLUMNS = listOf(
            "displayName", "nickname", "company", "department", "notes", "normalizedName",
        )
    }
}
