package app.sila.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource

/**
 * DDL صورة v3 لجدول `call_events` وجدول جلسات الاتصال الطارئ — **مصدر وحيد** يُستهلك من
 * مسارين يجب أن ينتجَا الشكل الفيزيائي نفسه حرفاً (تحقق Room من المخطط يقارن الأعمدة/
 * الافتراضيات/الفهارس بمطابقة تامة عبر `TableInfo`):
 * 1. [MIGRATION_2_3] — ترحيل قواعد Room v2 (إعادة بناء ونسخ — §I من تقرير بوابة T8-02).
 * 2. شيم الاعتماد الآمن (`legacy/LegacyDatabaseAdopter`) — قواعد `user_version = 0`
 *    تتحقق Room من مخططها مباشرةً **ضد كيانات المخطط الحالي** (مسار create/validate —
 *    قرار ADR-0011)، فيجب أن يوصلها الشيم لشكل v3 كاملاً لا شكل v2 التاريخي.
 *
 * **لا تُعدَّل النصوص هنا إلا مع تحديث كيانات Room وتصدير المخطط معاً** — أي انحراف
 * بين هذه النصوص والكيانات يُفشل فتح Room عند الاعتماد (fail-closed بالتصميم).
 *
 * التزامات الشكل (§G.3 — ADR-0006 كامل السبعة عشر حقلاً بـ 18 عمود SQL):
 * - المفتاح الأساسي `callLogId` TEXT NOT NULL (بلا إعادة تمكين؛ بلا AUTOINCREMENT).
 * - `e164` TEXT NOT NULL — **بلا** فهرس فريد (صفوف e164 مكررة بمفاتيح مختلفة تبقى).
 * - فهرس واحد حصراً `idx_call_events_ts(timestamp)` غير فريد — يُنشأ **بعد** إعادة التسمية.
 * - بلا مفاتيح أجرة وبلا قيود CHECK (ADR-0011: القيود توثيقية؛ محولات `valueOf` هي الحارس).
 * - بلا قيم افتراضية SQL (كيانات Room بلا DEFAULT — مطابقة حرفية مع `3.json`).
 */
internal object CallEventsV3Ddl {

    /** اسم جدول `call_events` الفيزيائي (ثابت لتفادي أخطاء إملائية بين المسارين). */
    private const val CALL_EVENTS_TABLE = "call_events"

    /** اسم الجدول المؤقت أثناء إعادة البناء والنسخ — لا يبقى بعد الالتزام أبداً. */
    private const val REBUILD_TEMP_TABLE = "call_events_v3_rebuild"

    /** اسم جدول جلسات الاتصال الطارئ (قرار D4 المُصادق — §W من الخطة). */
    private const val EMERGENCY_SESSIONS_TABLE = "emergency_ringing_sessions"

    /**
     * إنشاء جدول `call_events` بصورة v3 — ترتيب الأعمدة حرفي من §F.2 لتقرير بوابة T8-02
     * (نفسه ترتيب §H.2 لخريطة النسخ). يُستهلك باسم المؤقت أثناء الترحيل وباسم الجدول
     * النهائي في الشيم. ملاحظة: `NOT NULL` على مستوى العمود إلزامي لمفتاح TEXT —
     * PRIMARY KEY على مستوى الجدول وحده لا يمنح notnull=1 في SQLite (نفس فخ
     * `contact_phones` التاريخي الذي أصلحته شيم `rebuildContactPhonesWithNotNullPk`).
     */
    fun createCallEventsStatement(tableName: String): String =
        """
        CREATE TABLE IF NOT EXISTS `$tableName` (
            `callLogId` TEXT NOT NULL,
            `e164` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `primarySource` TEXT NOT NULL,
            `evidenceSources` TEXT NOT NULL,
            `remoteNumber` TEXT,
            `contactId` INTEGER,
            `direction` TEXT,
            `startedAt` INTEGER,
            `connectedAt` INTEGER,
            `endedAt` INTEGER,
            `durationSec` INTEGER,
            `outcome` TEXT NOT NULL,
            `association` TEXT NOT NULL,
            `confirmation` TEXT NOT NULL,
            `confidence` TEXT NOT NULL,
            `dedupeKey` TEXT,
            `simSlot` INTEGER,
            PRIMARY KEY(`callLogId`)
        )
        """.trimIndent()

    /** فهرس `call_events` الوحيد — يُنشأ **بعد** إعادة تسمية الجدول (يفصل مع DROP القديم). */
    val CREATE_CALL_EVENTS_INDEX: String =
        "CREATE INDEX IF NOT EXISTS `idx_call_events_ts` ON `$CALL_EVENTS_TABLE` (`timestamp`)"

    /**
     * جدول جلسات الاتصال الطارئ (قرار D4 — الحد الأدنى من البيانات: هوية جهة الاتصال
     * والعدادات وحالة الرنين حصراً — **بلا أي PII مكرر** مثل الاسم/الرقم؛ تُستعاد وقت
     * التشغيل عبر `contactId`). بلا فهارس إضافية (الحد الأدنى المُصادق حرفاً).
     */
    val CREATE_EMERGENCY_SESSIONS_TABLE: String =
        """
        CREATE TABLE IF NOT EXISTS `$EMERGENCY_SESSIONS_TABLE` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `contactId` INTEGER NOT NULL,
            `startedAt` INTEGER NOT NULL,
            `lastAttemptAt` INTEGER NOT NULL,
            `attempts` INTEGER NOT NULL,
            `status` TEXT NOT NULL
        )
        """.trimIndent()

    /**
     * نسخ §H.2 حرفياً: كل صف إرثي v2 → صف v3 (المحولات لن تعمل هنا — SQL خام؛
     * تعدادات TEXT تُنسخ بـ `.name` كما هي). التعبيرات من جدول §G حرفاً:
     * - [P] محفوظ حرفاً: callLogId، e164، timestamp، durationSec (شاملاً الصفر)،
     *   `source` → `primarySource` (تسمية جديدة بلا تغيير قيمة).
     * - [T] محوَّل موثق: direction (MISSED → NULL)، outcome (MISSED → UNANSWERED
     *   وإلا UNKNOWN)، evidenceSources (مجموعة مفردة من source)، association =
     *   UNASSOCIATED، confirmation = NOT_REQUIRED، confidence = LOW.
     * - [N] جديد بلا بيانات تاريخية → NULL: remoteNumber، contactId، startedAt،
     *   connectedAt، endedAt، dedupeKey، simSlot.
     */
    fun copyCallEventsFromLegacyStatement(targetTable: String): String =
        """
        INSERT INTO `$targetTable` (
            `callLogId`, `e164`, `timestamp`, `primarySource`, `evidenceSources`,
            `remoteNumber`, `contactId`, `direction`, `startedAt`, `connectedAt`,
            `endedAt`, `durationSec`, `outcome`, `association`, `confirmation`,
            `confidence`, `dedupeKey`, `simSlot`
        )
        SELECT
            `callLogId`,
            `e164`,
            `timestamp`,
            `source`,
            `source`,
            NULL,
            NULL,
            CASE WHEN `direction` = 'MISSED' THEN NULL ELSE `direction` END,
            NULL,
            NULL,
            NULL,
            `durationSec`,
            CASE WHEN `direction` = 'MISSED' THEN 'UNANSWERED' ELSE 'UNKNOWN' END,
            'UNASSOCIATED',
            'NOT_REQUIRED',
            'LOW',
            NULL,
            NULL
        FROM `$CALL_EVENTS_TABLE`
        """.trimIndent()

    /** أسماء قيم التعداد المسموحة — مشتقة من التعدادات نفسها (مصدر وحيد، لا ازدواج نصي). */
    private val legacyDirectionLiterals: String =
        CallDirection.entries.joinToString(separator = ", ") { "'$it'" }

    private val legacySourceLiterals: String =
        CallSource.entries.joinToString(separator = ", ") { "'$it'" }

    /**
     * عدّاد القيم التالفة (§H.4 — fail-closed): أي نص تعداد خارج القيم المعروفة، أو NULL
     * (مستحيل في مخطط v2 — وجوده يعني قاعدة تالفة)، يمنع الترحيل قبل أي كتابة.
     */
    val COUNT_MALFORMED_LEGACY_ENUM_TEXTS: String =
        "SELECT COUNT(*) FROM `$CALL_EVENTS_TABLE` WHERE " +
            "(`direction` IS NULL OR `direction` NOT IN ($legacyDirectionLiterals)) OR " +
            "(`source` IS NULL OR `source` NOT IN ($legacySourceLiterals))"

    /**
     * هل يحمل الجدول عموداً بعينه؟ (قراءة بنية فقط — قرارات إعادة البناء الفجوية).
     */
    private suspend fun tableHasColumn(db: SQLiteConnection, table: String, column: String): Boolean {
        val escapedTable = table.replace("`", "``")
        var found = false
        db.prepare("PRAGMA table_info(`$escapedTable`)").use { stmt ->
            while (stmt.step()) {
                if (stmt.getText(1) == column) {
                    found = true
                    break
                }
            }
        }
        return found
    }

    /**
     * فشل آمن على قيم إرثية تالفة (§H.4): يرمي قبل أي DDL/DML — الترحيل/الشيم لا
     * يُغيّران شيئاً (المعاملة تُرجع بالكامل). الرسالة عدّ حصراً — بلا قيم أو PII.
     */
    private suspend fun assertNoMalformedLegacyEnumTexts(db: SQLiteConnection) {
        db.prepare(COUNT_MALFORMED_LEGACY_ENUM_TEXTS).use { stmt ->
            check(stmt.step()) { "T8-02: COUNT query returned no row — unexpected SQLite state" }
            val malformed = stmt.getLong(0)
            check(malformed == 0L) {
                "MIGRATION_2_3: malformed legacy enum text in call_events — fail-closed (affectedRows=$malformed)"
            }
        }
    }

    /**
     * تسلسل إعادة البناء والنسخ الموحد (§I من تقرير بوابة T8-02) — يُستهلك من
     * [MIGRATION_2_3] ومن شيم الاعتماد معاً بحيث يستحيل انحراف المسارين:
     *
     * 1. تحقق fail-closed من نصوص التعداد الإرثية (قراءة فقط — يرمي قبل أي كتابة).
     * 2. إنشاء الجدول المؤقت بصورة v3.
     * 3. نسخ كل الصفوف بتعبيرات §H.2.
     * 4. إسقاط الجدول القديم ثم إعادة تسمية المؤقت.
     * 5. إنشاء الفهرس الوحيد بعد إعادة التسمية.
     * 6. إنشاء جدول جلسات الطوارئ (قرار D4) — `IF NOT EXISTS` (idempotent).
     *
     * يستهدف فجوات الحالة idempotently: جدول `call_events` بالصورة v3 أصلاً يُتخطى
     * (بلا إعادة نسخ)، وجدول غائب الشكلين مستحيل عبر تصنيف البصمة (UNKNOWN قبل الوصول
     * هنا) — أي وصول غير متوقع يُعامَل فشلاً آمناً. **لا معاملة داخلية هنا**: الترحيل
     * يعمل داخل معاملة Room الحصرية (تحقق فعلي بالبايت-كود: `BaseRoomConnectionManager.
     * configureDatabase` يغلّف مسار الترحيل بـ BEGIN EXCLUSIVE/END TRANSACTION)، والشيم
     * يغلّف الخطوات كلها بمعاملة `BEGIN IMMEDIATE` الخاصة به.
     */
    suspend fun rebuildCallEventsToV3AndCreateEmergencySessionsTable(db: SQLiteConnection) {
        val hasLegacyShape = tableHasColumn(db, CALL_EVENTS_TABLE, "source")
        val hasV3Shape = tableHasColumn(db, CALL_EVENTS_TABLE, "primarySource")
        when {
            hasLegacyShape -> {
                assertNoMalformedLegacyEnumTexts(db)
                db.executeSQL("DROP TABLE IF EXISTS `$REBUILD_TEMP_TABLE`")
                db.executeSQL(createCallEventsStatement(REBUILD_TEMP_TABLE))
                db.executeSQL(copyCallEventsFromLegacyStatement(REBUILD_TEMP_TABLE))
                db.executeSQL("DROP TABLE `$CALL_EVENTS_TABLE`")
                db.executeSQL("ALTER TABLE `$REBUILD_TEMP_TABLE` RENAME TO `$CALL_EVENTS_TABLE`")
                db.executeSQL(CREATE_CALL_EVENTS_INDEX)
            }

            hasV3Shape -> Unit

            else -> throw IllegalStateException(
                "MIGRATION_2_3: call_events matches neither the v2 nor the v3 shape — fail-closed",
            )
        }
        db.executeSQL(CREATE_EMERGENCY_SESSIONS_TABLE)
    }
}
