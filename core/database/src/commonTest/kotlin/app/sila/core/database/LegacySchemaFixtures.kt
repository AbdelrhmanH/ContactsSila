package app.sila.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** مستويات تجهيزات المخطط القديم — تقابل `LegacySchemaLevel` الداخلية للاعتماد. */
internal enum class LegacyFixtureLevel { L0, L1, L2_OR_L3 }

/**
 * بنّاء تجهيزات المخطط القديم لإصدار-صفر لاختبارات الاعتماد (PR B · ADR-0011 §10 في الخطة).
 *
 * الـ DDL هنا **حرفي** من تاريخ Git لواجهة الإنتاج (الأساس @ `84b530a` للـ L0، وترقية
 * v1→v2 للتوافق `Issue #23`، وT3 `Issue #24` لعمود FTS `department`) — نفس ما كان يُكتب
 * فعلاً على أجهزة المستخدمين. البنية حصراً: لا بذر تلقائي — البيانات عبر [seed].
 *
 * يُترك فهرس FTS فارغاً عمداً (الإدراج الخام عبر SQL لا يملك ArabicTextNormalizer) —
 * شيم الاعتماد هي المسؤولة عن إعادة التعبئة idempotently، وهذا ما تختبره الاختبارات.
 */
internal object LegacySchemaFixtures {

    /** أنشئ قاعدة legacy عند [path] بالمستوى [level] ثم اربط بذر [seed] — يجب أن يكون الملف جديداً. */
    suspend fun createLegacyDatabase(
        path: String,
        level: LegacyFixtureLevel,
        seed: suspend (SQLiteConnection) -> Unit = {},
    ) {
        BundledSQLiteDriver().open(path).use { conn ->
            createCoreTables(conn)
            if (level != LegacyFixtureLevel.L0) {
                applyV2SyncUpgrade(conn)
            }
            if (level == LegacyFixtureLevel.L2_OR_L3) {
                applyT3FtsDepartment(conn)
            }
            seed(conn)
        }
    }

    // ===== L0 (@ 84b530a): 7 جداول — بلا FTS وبلا نقاط استئناف =====

    private suspend fun createCoreTables(conn: SQLiteConnection) {
        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY NOT NULL,
                systemContactId TEXT NOT NULL,
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
            """.trimIndent()
        )
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contacts_category ON contacts(category)")
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contacts_norm_name ON contacts(normalizedName)")
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contacts_fav ON contacts(isFavorite)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_phones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contactId INTEGER NOT NULL,
                raw TEXT NOT NULL,
                e164 TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'MOBILE',
                isPrimary INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_cid ON contact_phones(contactId)")
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contact_phones_e164 ON contact_phones(e164)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS arham (
                contactId INTEGER PRIMARY KEY NOT NULL,
                relation TEXT NOT NULL,
                layer TEXT NOT NULL,
                parentContactId INTEGER,
                frequency TEXT NOT NULL,
                lastContactedAt INTEGER,
                pausedUntil INTEGER,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS services (
                cloudId TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                professionId TEXT NOT NULL,
                professionNameAr TEXT NOT NULL,
                professionNameEn TEXT NOT NULL,
                phoneE164 TEXT NOT NULL,
                visibility TEXT NOT NULL,
                trustScore REAL NOT NULL,
                uniqueCallers INTEGER NOT NULL,
                retentionRate REAL NOT NULL,
                ratingAvg REAL NOT NULL,
                ratingCount INTEGER NOT NULL,
                isHot INTEGER NOT NULL DEFAULT 0,
                city TEXT,
                ownerVerified INTEGER NOT NULL DEFAULT 0,
                isSavedByUser INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_services_phone ON services(phoneE164)")
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_services_prof ON services(professionId)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS call_events (
                callLogId TEXT PRIMARY KEY NOT NULL,
                e164 TEXT NOT NULL,
                direction TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_call_events_ts ON call_events(timestamp)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS arham_inbox (
                id INTEGER PRIMARY KEY NOT NULL,
                senderUid TEXT NOT NULL,
                senderName TEXT NOT NULL,
                recipientUid TEXT NOT NULL,
                messageText TEXT NOT NULL,
                direction TEXT NOT NULL,
                status TEXT NOT NULL,
                sentAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_arham_inbox_sent ON arham_inbox(sentAt)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS dispute_logs (
                id INTEGER PRIMARY KEY NOT NULL,
                serviceCloudId TEXT NOT NULL,
                description TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                resolvedAt INTEGER
            )
            """.trimIndent()
        )
    }

    // ===== ترقية v1→v2 (Issue #23): أعمدة التكافؤ الستة عشر + نقاط الاستئناف + FTS بخمسة أعمدة =====

    private suspend fun applyV2SyncUpgrade(conn: SQLiteConnection) {
        // نفس قائمة ensureContactParityColumns الحرفية من واجهة التاريخ (TEXT بلا default =
        // nullable؛ NOT NULL DEFAULT 0 للمنطقية و'' لـ fileAs).
        val existingColumns = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(contacts)").use { stmt ->
            while (stmt.step()) existingColumns += stmt.getText(1)
        }
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
            "systemDeletedAt" to "INTEGER",
        ).forEach { (name, ddl) ->
            if (name !in existingColumns) conn.executeSQL("ALTER TABLE contacts ADD COLUMN $name $ddl")
        }
        conn.executeSQL("CREATE INDEX IF NOT EXISTS idx_contacts_system_id ON contacts(systemContactId)")

        conn.executeSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts_sync_checkpoints (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )

        conn.executeSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS contacts_fts USING fts4(" +
                "displayName, nickname, company, notes, normalizedName)"
        )
    }

    // ===== ترقية T3 (Issue #24): FTS بستة أعمدة (بإعادة إنشاء — FTS4 بلا ADD COLUMN) =====

    private suspend fun applyT3FtsDepartment(conn: SQLiteConnection) {
        conn.executeSQL("DROP TABLE IF EXISTS contacts_fts")
        conn.executeSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS contacts_fts USING fts4(" +
                "displayName, nickname, company, department, notes, normalizedName)"
        )
    }
}
