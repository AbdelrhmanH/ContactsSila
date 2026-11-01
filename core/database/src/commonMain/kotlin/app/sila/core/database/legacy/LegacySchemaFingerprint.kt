package app.sila.core.database.legacy

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import app.sila.core.database.legacy.LegacySchemaLevel.L0
import app.sila.core.database.legacy.LegacySchemaLevel.L1
import app.sila.core.database.legacy.LegacySchemaLevel.L2_OR_L3
import app.sila.core.database.legacy.LegacySchemaLevel.PARITY_DONE
import app.sila.core.database.legacy.LegacySchemaLevel.ROOM_MANAGED
import app.sila.core.database.legacy.LegacySchemaLevel.UNKNOWN

/**
 * بصمة مخطط قاعدة البيانات القديمة — **بنية حصراً، بلا أي بيانات مستخدم**
 * (لا أسماء جهات ولا أرقام ولا أي قيم صفوف — ADR-0011 §البصمة).
 *
 * تُبنى من `sqlite_master` و `PRAGMA table_info/index_list/index_info/foreign_key_list`
 * و `PRAGMA user_version` — وهي نفس عناصر الفحص التي يستخدمها Room في التحقق.
 */
internal data class LegacySchemaSnapshot(
    val userVersion: Int,
    /** أسماء جداول المستخدم (بلا جداول sqlite_* الداخلية) بترتيب حتمي. */
    val tableNames: List<String>,
    val columnsByTable: Map<String, List<LegacyColumn>>,
    val indicesByTable: Map<String, List<LegacyIndex>>,
    val foreignKeyCountsByTable: Map<String, Int>,
    /** أعمدة `contacts_fts` بترتيب DDL — `null` إن غاب الجدول. */
    val ftsColumns: List<String>?,
    /** خيارات FTS المستخرجة من تعريف sqlite_master (tokenize=… إلخ) — بلا الأعمدة. */
    val ftsOptions: Set<String>,
    val triggerNames: List<String>,
) {
    /** تلخيص بصمة آمن للسجلات/التقارير (أسماء بنية فقط — لا PII). */
    fun safeSummary(): String =
        "user_version=$userVersion tables=[${tableNames.joinToString(",")}]" +
            " contactsColumns=${columnsByTable["contacts"]?.size ?: 0}" +
            " ftsColumns=${ftsColumns?.size ?: -1}" +
            " ftsOptions=[${ftsOptions.sorted().joinToString(",")}]" +
            " triggers=${triggerNames.size}" +
            " foreignKeyConstraints=${foreignKeyCountsByTable.values.sum()}"
}

/** عمود فيزيائي كما تُبلِغه `PRAGMA table_info`. */
internal data class LegacyColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val pkPosition: Int,
    val defaultValue: String?,
)

/** فهرس فيزيائي كما تُبلِغه `PRAGMA index_list/index_info`. */
internal data class LegacyIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

/**
 * مستويات المخطط القديم المدعومة (خطة PR B §10 — الأشكال التاريخية المُعاد بناؤها من
 * تاريخ Git عند `84b530a`/`8627b47`/`098c19b`/`dd04f1f`، كلها `user_version = 0`).
 */
internal enum class LegacySchemaLevel {
    /** أول إصدار (7 جداول — بلا FTS وبلا نقاط استئناف — contacts بـ 13 عموداً). */
    L0,

    /** أعمدة تكافؤ الكيان + نقاط استئناف + FTS بخمسة أعمدة (قبل T3). */
    L1,

    /** FTS بستة أعمدة (بعد T3) — يشمل L3 (بوابة F-01 لم تُغيّر المخطط الفيزيائي). */
    L2_OR_L3,

    /** المخطط الهدف كاملاً (incl. `contact_phones` PK يعلن NOT NULL) — لا شيم مطلوب. */
    PARITY_DONE,

    /** قاعدة تديرها Room أصلاً (`user_version != 0`) — يتصرف Room مباشرة. */
    ROOM_MANAGED,

    /** تثبيت جديد فارغ — Room ينشئ المخطط مباشرة. */
    FRESH,

    /** مخطط غير مصنَّف — **فشل آمن** (لا تعديل ولا فتح). */
    UNKNOWN,
}

/**
 * قارئ ومصنِّف بصمة المخطط: يحدد المستوى القديم المدعوم حصراً من البصمة البنيوية.
 * القراءة **قراءة فقط** — لا تعدّل الملف إطلاقاً.
 */
internal object LegacySchemaFingerprint {

    /** جداول المستخدم المستهدفة (8 فعلية + FTS — بلا الكيانين الشبحيين، ADR-0011). */
    private val TARGET_TABLES = setOf(
        "contacts", "contact_phones", "arham", "services", "call_events",
        "arham_inbox", "dispute_logs", "contacts_sync_checkpoints", "contacts_fts",
    )

    private val CORE_TABLES = setOf(
        "contacts", "contact_phones", "arham", "services", "call_events", "arham_inbox", "dispute_logs",
    )

    private const val SQLITE_INTERNAL_PREFIX = "sqlite_"

    /** جداول الظل المشتقة من `contacts_fts` (FTS4) — مشتقة حصراً ولا تُصنَّف كجداول غير معروفة. */
    private const val FTS_SHADOW_PREFIX = "contacts_fts_"

    /** أعمدة contacts في L0 (الأصل التاريخي @ `84b530a`). */
    private val L0_CONTACT_COLUMNS = setOf(
        "id", "systemContactId", "displayName", "firstName", "lastName", "company",
        "photoUri", "category", "isFavorite", "excludeFromSmartFilter", "normalizedName",
        "createdAt", "updatedAt",
    )

    /** أعمدة contacts الهدف (29). */
    private val TARGET_CONTACT_COLUMNS = setOf(
        "id", "systemContactId", "lookupKey", "accountType", "accountName", "displayName",
        "fileAs", "prefix", "firstName", "middleName", "lastName", "suffix", "nickname",
        "phoneticName", "company", "department", "photoUri", "category", "isFavorite",
        "customRingtoneUri", "sendToVoicemail", "isBlocked", "notes", "linkedServiceCloudId",
        "excludeFromSmartFilter", "normalizedName", "createdAt", "updatedAt", "systemDeletedAt",
    )

    /**
     * أعمدة الجداول الأساسية الأخرى — لم تتغير عبر L0..L3 ويجب أن تطابق حرفياً قبل قبول البصمة
     * (أي عمود ناقص أو زائد في أي جدول أساسي ⇒ `UNKNOWN` فشل آمن قبل تشغيل الشيم).
     */
    private val INVARIANT_TABLE_COLUMNS: Map<String, Set<String>> = mapOf(
        "contact_phones" to setOf("id", "contactId", "raw", "e164", "type", "isPrimary"),
        "arham" to setOf(
            "contactId", "relation", "layer", "parentContactId",
            "frequency", "lastContactedAt", "pausedUntil", "createdAt",
        ),
        "services" to setOf(
            "cloudId", "name", "professionId", "professionNameAr", "professionNameEn",
            "phoneE164", "visibility", "trustScore", "uniqueCallers", "retentionRate",
            "ratingAvg", "ratingCount", "isHot", "city", "ownerVerified", "isSavedByUser",
        ),
        "call_events" to setOf("callLogId", "e164", "direction", "durationSec", "timestamp", "source"),
        "arham_inbox" to setOf(
            "id", "senderUid", "senderName", "recipientUid",
            "messageText", "direction", "status", "sentAt",
        ),
        "dispute_logs" to setOf(
            "id", "serviceCloudId", "description",
            "status", "createdAt", "updatedAt", "resolvedAt",
        ),
    )

    private val CHECKPOINTS_COLUMNS = setOf("key", "value")

    private val FTS6_COLUMNS = listOf(
        "displayName", "nickname", "company", "department", "notes", "normalizedName",
    )

    private val TARGET_CONTACT_INDEX_NAMES = setOf(
        "idx_contacts_system_id", "idx_contacts_category", "idx_contacts_norm_name", "idx_contacts_fav",
    )

    /** يقرأ البصمة كاملة من اتصال مفتوح (قراءة فقط). */
    suspend fun readSnapshot(connection: SQLiteConnection): LegacySchemaSnapshot {
        val userVersion = connection.prepare("PRAGMA user_version").use { stmt ->
            stmt.step()
            stmt.getLong(0).toInt()
        }

        val masterRows = mutableListOf<Pair<String, String>>() // (type, name)
        connection.prepare("SELECT type, name FROM sqlite_master").use { stmt ->
            while (stmt.step()) masterRows += stmt.getText(0) to stmt.getText(1)
        }

        val userTables = masterRows
            .filter { (type, name) -> type == "table" && !name.startsWith(SQLITE_INTERNAL_PREFIX) }
            .map { it.second }
            .sorted()

        val triggers = masterRows.filter { (type, _) -> type == "trigger" }.map { it.second }.sorted()

        val columnsByTable = userTables.associateWith { table ->
            connection.prepare("PRAGMA table_info($table)").use { stmt ->
                val columns = mutableListOf<LegacyColumn>()
                while (stmt.step()) {
                    columns += LegacyColumn(
                        name = stmt.getText(1),
                        type = stmt.getText(2),
                        notNull = stmt.getLong(3) != 0L,
                        pkPosition = stmt.getLong(5).toInt(),
                        defaultValue = if (stmt.isNull(4)) null else stmt.getText(4),
                    )
                }
                columns
            }
        }

        val indicesByTable = userTables.associateWith { table ->
            connection.prepare("PRAGMA index_list($table)").use { listStmt ->
                val indices = mutableListOf<LegacyIndex>()
                while (listStmt.step()) {
                    val indexName = listStmt.getText(1)
                    val isUnique = listStmt.getLong(2) != 0L
                    val indexColumns = connection.prepare("PRAGMA index_info($indexName)").use { infoStmt ->
                        val cols = mutableListOf<String>()
                        while (infoStmt.step()) cols += infoStmt.getText(2)
                        cols
                    }
                    indices += LegacyIndex(name = indexName, unique = isUnique, columns = indexColumns)
                }
                indices
            }
        }

        val foreignKeyCountsByTable = userTables.associateWith { table ->
            connection.prepare("PRAGMA foreign_key_list($table)").use { stmt ->
                var count = 0
                while (stmt.step()) count++
                count
            }
        }

        val ftsColumns: List<String>? = columnsByTable["contacts_fts"]?.map { it.name }
        val ftsOptions = if ("contacts_fts" in userTables) {
            connection.prepare("SELECT sql FROM sqlite_master WHERE name = 'contacts_fts'").use { stmt ->
                if (stmt.step() && !stmt.isNull(0)) {
                    LegacyFtsDdl.optionsOf(stmt.getText(0))
                } else {
                    emptySet()
                }
            }
        } else {
            emptySet()
        }

        return LegacySchemaSnapshot(
            userVersion = userVersion,
            tableNames = userTables,
            columnsByTable = columnsByTable,
            indicesByTable = indicesByTable,
            foreignKeyCountsByTable = foreignKeyCountsByTable,
            ftsColumns = ftsColumns,
            ftsOptions = ftsOptions,
            triggerNames = triggers,
        )
    }

    /**
     * تصنيف البصمة إلى مستوى مدعوم أو `UNKNOWN` (فشل آمن).
     * كل مستوى **يشمل** حالات المقاطعة الجزئية للترقيات/الشيم السابقة (idempotent —
     * الشيم يستهدف فجوات الحالة لا عدّادات إصدارات).
     */
    fun classify(snapshot: LegacySchemaSnapshot): LegacySchemaLevel {
        if (snapshot.userVersion != 0) return ROOM_MANAGED

        val tables = snapshot.tableNames.toSet()
        // تثبيت جديد فارغ — بلا جداول مستخدم إطلاقاً.
        if (tables.isEmpty()) return LegacySchemaLevel.FRESH

        // جداول الظل (contacts_fts_content/segments/segdir…) مشتقة من FTS4 نفسه — ليست «غير معروفة».
        val unexpectedTables = tables - TARGET_TABLES - tables.filter { it.startsWith(FTS_SHADOW_PREFIX) }.toSet()
        val contactsColumns = snapshot.columnsByTable["contacts"]?.map { it.name }?.toSet() ?: emptySet()
        val fts = snapshot.ftsColumns

        // قيود رفض صارمة قبل التصنيف: مشغّلات أو قيود FK أو جداول غريبة أو نواقص أساسية ⇒ UNKNOWN.
        if (snapshot.triggerNames.isNotEmpty()) return UNKNOWN
        if (snapshot.foreignKeyCountsByTable.values.any { it > 0 }) return UNKNOWN
        if (unexpectedTables.isNotEmpty()) return UNKNOWN
        if ((CORE_TABLES - tables).isNotEmpty()) return UNKNOWN

        // التحقق الدقيق من أعمدة الجداول الأساسية غير المتغيرة ونقاط الاستئناف (إن وُجدت):
        if (!hasExpectedInvariantColumns(snapshot, tables)) return UNKNOWN

        // يجب أن يحتوي contacts على أعمدة L0 على الأقل، وبلا أي عمود خارج الهدف.
        if (!contactsColumns.containsAll(L0_CONTACT_COLUMNS) || !TARGET_CONTACT_COLUMNS.containsAll(contactsColumns)) {
            return UNKNOWN
        }

        // الحالة الهدف كاملة (مثلاً: انقطاع بعد الشيم وقبل فتح Room) — لا شيم.
        // خيارات FTS جزء من الشكل الهدف: توكنِزِر/محتوى أجنبي (حتى بأعمدة مطابقة) ⇒ ليس الهدف.
        if (contactsColumns == TARGET_CONTACT_COLUMNS && ftsShapeIsTarget(snapshot) &&
            "contacts_sync_checkpoints" in tables &&
            hasTargetContactsIndices(snapshot) && phonesPkDeclaresNotNull(snapshot)
        ) {
            return PARITY_DONE
        }

        val hasCheckpoints = "contacts_sync_checkpoints" in tables
        val hasFts = fts != null

        return when {
            !hasFts && !hasCheckpoints -> L0
            // أعمدة FTS الهدف **وخياراته** مطابقة للثابت الموحد — التوأمة الكاملة:
            ftsShapeIsTarget(snapshot) -> L2_OR_L3
            // شكل FTS غير مطابق (أعمدة غير معروفة **أو خيارات أجنبية** مثل tokenize=porter
            // أو content=، أو انقطاع جزئي بين إنشاء نقاط الاستئناف وإنشاء FTS) — يُعالَج كـ L1:
            // الشيم يسقط FTS ويعيد إنشاءه بالـ DDL الموحد من contacts حصراً (بلا فقدان بيانات).
            hasFts || hasCheckpoints -> L1
            else -> UNKNOWN
        }
    }

    private fun hasExpectedInvariantColumns(snapshot: LegacySchemaSnapshot, tables: Set<String>): Boolean {
        for ((table, expectedColumns) in INVARIANT_TABLE_COLUMNS) {
            val actualColumns = snapshot.columnsByTable[table]?.map { it.name }?.toSet() ?: return false
            if (actualColumns != expectedColumns) return false
        }
        if ("contacts_sync_checkpoints" in tables) {
            val actualCheckpoints = snapshot.columnsByTable["contacts_sync_checkpoints"]
                ?.map { it.name }?.toSet() ?: return false
            if (actualCheckpoints != CHECKPOINTS_COLUMNS) return false
        }
        return true
    }

    /** شكل FTS الهدف: أعمدة الستّة **وخيارات التعريف مطابقة للثابت الموحد** (فارغة — SIMPLE). */
    private fun ftsShapeIsTarget(snapshot: LegacySchemaSnapshot): Boolean =
        snapshot.ftsColumns == FTS6_COLUMNS && snapshot.ftsOptions == LegacyFtsDdl.TARGET_OPTIONS

    private fun hasTargetContactsIndices(snapshot: LegacySchemaSnapshot): Boolean {
        val contactIndices = snapshot.indicesByTable["contacts"].orEmpty()
        if (!contactIndices.map { it.name }.toSet()
                .containsAll(TARGET_CONTACT_INDEX_NAMES)
        ) {
            return false
        }
        // فهرس الهوية يجب أن يكون غير فريد (Decision 2 — ADR-0011).
        return contactIndices.none { it.name == "idx_contacts_system_id" && it.unique }
    }

    /** `contact_phones` يعلن NOT NULL في PK؟ (شرط مطابقة Room — أُثبت بالتجربة). */
    private fun phonesPkDeclaresNotNull(snapshot: LegacySchemaSnapshot): Boolean =
        snapshot.columnsByTable["contact_phones"]
            ?.firstOrNull { it.pkPosition > 0 }
            ?.notNull == true
}
