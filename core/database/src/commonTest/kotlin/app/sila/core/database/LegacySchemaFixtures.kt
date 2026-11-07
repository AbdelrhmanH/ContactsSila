package app.sila.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.legacy.LegacyFtsDdl
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource

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

    // ===== قاعدة Room v2 كاملة (T8-02 · Issue #29) — مدخل ترحيل 2→3 =====

    /**
     * هوية مخطط Room v2 — من `2.json` ومن `RoomOpenDelegate(2, …)` المولّد عند أساس
     * `bbcb77b` حرفاً (تُكتب في `room_master_table` كما يفعل Room عند إنشاء v2).
     */
    internal const val ROOM_V2_IDENTITY_HASH: String = "78067beac275e2ed5fa928883ad2c7c5"

    /**
     * DDL قاعدة Room v2 كاملة — **حرفي** من `createAllTables` في
     * `SilaRoomDatabase_Impl.kt` المولّد لـ v3.0.3 عند إصدار المخطط 2 (أساس `bbcb77b`):
     * هذه هي العبارات التي كتبتها Room فعلاً على أجهزة المستخدمين عند إنشاء v2 أو
     * ختمها بعد ترحيل 1→2. ترتيب العبارات لا يهم (Room يُنشئها دفعة واحدة).
     */
    private val ROOM_V2_CREATE_STATEMENTS: List<String> = listOf(
        // contacts (29 عموداً — v2) + فهارسها الأربعة.
        "CREATE TABLE IF NOT EXISTS `contacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `systemContactId` TEXT NOT NULL, `lookupKey` TEXT, `accountType` TEXT, `accountName` TEXT, `displayName` TEXT NOT NULL, `fileAs` TEXT NOT NULL DEFAULT '', `prefix` TEXT, `firstName` TEXT, `middleName` TEXT, `lastName` TEXT, `suffix` TEXT, `nickname` TEXT, `phoneticName` TEXT, `company` TEXT, `department` TEXT, `photoUri` TEXT, `category` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL DEFAULT 0, `customRingtoneUri` TEXT, `sendToVoicemail` INTEGER NOT NULL DEFAULT 0, `isBlocked` INTEGER NOT NULL DEFAULT 0, `notes` TEXT, `linkedServiceCloudId` TEXT, `excludeFromSmartFilter` INTEGER NOT NULL DEFAULT 0, `normalizedName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, `systemDeletedAt` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `idx_contacts_system_id` ON `contacts` (`systemContactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contacts_category` ON `contacts` (`category`)",
        "CREATE INDEX IF NOT EXISTS `idx_contacts_norm_name` ON `contacts` (`normalizedName`)",
        "CREATE INDEX IF NOT EXISTS `idx_contacts_fav` ON `contacts` (`isFavorite`)",
        // contact_phones (v2 — T6: label + sortOrder، وPK يعلن NOT NULL) + فهارسها.
        "CREATE TABLE IF NOT EXISTS `contact_phones` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `raw` TEXT NOT NULL, `e164` TEXT NOT NULL, `type` TEXT NOT NULL DEFAULT 'MOBILE', `isPrimary` INTEGER NOT NULL DEFAULT 0, `label` TEXT, `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_phones_cid` ON `contact_phones` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_phones_e164` ON `contact_phones` (`e164`)",
        // جداول الحقول القياسية الخمسة (v2 — T6) + فهارسها.
        "CREATE TABLE IF NOT EXISTS `contact_emails` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `address` TEXT NOT NULL, `type` TEXT NOT NULL DEFAULT 'PERSONAL', `label` TEXT, `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_emails_cid` ON `contact_emails` (`contactId`)",
        "CREATE TABLE IF NOT EXISTS `contact_addresses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `street` TEXT, `city` TEXT, `region` TEXT, `postalCode` TEXT, `country` TEXT, `type` TEXT NOT NULL DEFAULT 'HOME', `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_addresses_cid` ON `contact_addresses` (`contactId`)",
        "CREATE TABLE IF NOT EXISTS `contact_dates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `type` TEXT NOT NULL, `label` TEXT, `month` INTEGER NOT NULL, `day` INTEGER NOT NULL, `year` INTEGER, `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_dates_cid` ON `contact_dates` (`contactId`)",
        "CREATE TABLE IF NOT EXISTS `contact_custom_fields` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `label` TEXT NOT NULL, `value` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_custom_fields_cid` ON `contact_custom_fields` (`contactId`)",
        "CREATE TABLE IF NOT EXISTS `contact_websites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, `url` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS `idx_contact_websites_cid` ON `contact_websites` (`contactId`)",
        // arham / services + فهارسها.
        "CREATE TABLE IF NOT EXISTS `arham` (`contactId` INTEGER NOT NULL, `relation` TEXT NOT NULL, `layer` TEXT NOT NULL, `parentContactId` INTEGER, `frequency` TEXT NOT NULL, `lastContactedAt` INTEGER, `pausedUntil` INTEGER, `createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`contactId`))",
        "CREATE TABLE IF NOT EXISTS `services` (`cloudId` TEXT NOT NULL, `name` TEXT NOT NULL, `professionId` TEXT NOT NULL, `professionNameAr` TEXT NOT NULL, `professionNameEn` TEXT NOT NULL, `phoneE164` TEXT NOT NULL, `visibility` TEXT NOT NULL, `trustScore` REAL NOT NULL, `uniqueCallers` INTEGER NOT NULL, `retentionRate` REAL NOT NULL, `ratingAvg` REAL NOT NULL, `ratingCount` INTEGER NOT NULL, `isHot` INTEGER NOT NULL DEFAULT 0, `city` TEXT, `ownerVerified` INTEGER NOT NULL DEFAULT 0, `isSavedByUser` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`cloudId`))",
        "CREATE INDEX IF NOT EXISTS `idx_services_phone` ON `services` (`phoneE164`)",
        "CREATE INDEX IF NOT EXISTS `idx_services_prof` ON `services` (`professionId`)",
        // call_events (v2 — ستة أعمدة) + فهرسها الوحيد.
        "CREATE TABLE IF NOT EXISTS `call_events` (`callLogId` TEXT NOT NULL, `e164` TEXT NOT NULL, `direction` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`callLogId`))",
        "CREATE INDEX IF NOT EXISTS `idx_call_events_ts` ON `call_events` (`timestamp`)",
        // arham_inbox + فهرسها / dispute_logs / نقاط الاستئناف.
        "CREATE TABLE IF NOT EXISTS `arham_inbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `senderUid` TEXT NOT NULL, `senderName` TEXT NOT NULL, `recipientUid` TEXT NOT NULL, `messageText` TEXT NOT NULL, `direction` TEXT NOT NULL, `status` TEXT NOT NULL, `sentAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `idx_arham_inbox_sent` ON `arham_inbox` (`sentAt`)",
        "CREATE TABLE IF NOT EXISTS `dispute_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serviceCloudId` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `resolvedAt` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `contacts_sync_checkpoints` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    )

    /**
     * أنشئ قاعدة Room v2 كاملة عند [path] — صورة الجهاز الحرفية قبل ترحيل T8-02:
     * الكيانات الـ 13 بDDL المولّد الحرفي ([ROOM_V2_CREATE_STATEMENTS]) + `contacts_fts`
     * (بعبارة [LegacyFtsDdl.CREATE_CONTACTS_FTS] نفسها — كما يصنعه Callback `onCreate`
     * عند ولادة القاعدة) + `room_master_table` بهوية v2 ([ROOM_V2_IDENTITY_HASH]) +
     * `PRAGMA user_version = 2`. بذر البيانات عبر [seed] حصراً — بلا بذر تلقائي.
     *
     * مستهدفة اختبارات [MIGRATION_2_3]: فتح Room على الملف يُشغّل الترحيل 2→3 داخل
     * معاملته الحصرية ثم يتحقق من الناتج ضد كيانات v3 — أي انحراف في صورة v2 يُكتشف
     * كمطابقة فاشلة (fail-closed) وليس كمرور صامت.
     */
    suspend fun createFullRoomV2Database(
        path: String,
        seed: suspend (SQLiteConnection) -> Unit = {},
    ) {
        BundledSQLiteDriver().open(path).use { conn ->
            ROOM_V2_CREATE_STATEMENTS.forEach { conn.executeSQL(it) }
            conn.executeSQL(LegacyFtsDdl.CREATE_CONTACTS_FTS)
            conn.executeSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
            )
            conn.executeSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$ROOM_V2_IDENTITY_HASH')"
            )
            conn.executeSQL("PRAGMA user_version = 2")
            seed(conn)
        }
    }
}

/**
 * كيان `call_events` v3 من التوقيع الإرثي v2 — بنفس تعبيرات §H.2 حرفاً (تقابل
 * `CallEventsV3Ddl.copyCallEventsFromLegacyStatement` في Kotlin): `source` →
 * `primarySource` + مجموعة أدلة مفردة، `MISSED` → `direction = null` + UNANSWERED
 * (وإلا الاتجاه كما هو + UNKNOWN)، القيم الافتراضية الثلاث الثابتة، وكل الحقول
 * الجديدة NULL عدا `durationSec` المحفوظ حرفاً شاملاً الصفر. تستبدل مواقع البناء
 * الموضعية القديمة في الاختبارات دون تغيير دلالتها (صف إرثي v2 بصدد تنظيف زمني).
 */
internal fun legacyCallEvent(
    callLogId: String,
    e164: String,
    direction: CallDirection,
    durationSec: Int,
    timestampMillis: Long,
    source: CallSource,
): CallEventEntity = CallEventEntity(
    callLogId = callLogId,
    e164 = e164,
    timestamp = timestampMillis,
    primarySource = source,
    evidenceSources = setOf(source),
    remoteNumber = null,
    contactId = null,
    direction = if (direction == CallDirection.MISSED) null else direction,
    startedAt = null,
    connectedAt = null,
    endedAt = null,
    durationSec = durationSec,
    outcome = if (direction == CallDirection.MISSED) CallOutcome.UNANSWERED else CallOutcome.UNKNOWN,
    association = CallAssociation.UNASSOCIATED,
    confirmation = CallConfirmation.NOT_REQUIRED,
    confidence = CallConfidence.LOW,
    dedupeKey = null,
    simSlot = null,
)
