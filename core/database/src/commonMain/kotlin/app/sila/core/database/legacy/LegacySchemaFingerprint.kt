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
) {
    fun normalized(): LegacyColumn = copy(
        type = type.trim().uppercase(),
        defaultValue = defaultValue?.trim(),
    )
}

/** فهرس فيزيائي كما تُبلِغه `PRAGMA index_list/index_xinfo`. */
internal data class LegacyIndex(
    val name: String,
    val unique: Boolean,
    val origin: String = "c",
    val partial: Boolean = false,
    val columns: List<String>,
    val orders: List<String> = columns.map { "ASC" },
    val hasExpression: Boolean = false,
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

    /** جداول الظل الخمسة القياسية المشتقة من `contacts_fts` (FTS4) حصراً عند وجود الجدول الافتراضي. */
    private val FTS_SHADOW_TABLES = setOf(
        "contacts_fts_content",
        "contacts_fts_segments",
        "contacts_fts_segdir",
        "contacts_fts_docsize",
        "contacts_fts_stat",
    )

    /** أعمدة contacts في L0 بالمواصفات الفيزيائية الكاملة (الأصل التاريخي @ `84b530a`). */
    private val L0_CONTACT_COLUMN_SPECS: Set<LegacyColumn> = setOf(
        LegacyColumn("id", "INTEGER", notNull = true, pkPosition = 1, defaultValue = null),
        LegacyColumn("systemContactId", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("displayName", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("firstName", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("lastName", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("company", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("photoUri", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("category", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("isFavorite", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        LegacyColumn("excludeFromSmartFilter", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        LegacyColumn("normalizedName", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("createdAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        LegacyColumn("updatedAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
    )

    /** أعمدة تكافؤ الكيان الستة عشر المضافة في ترقية v1→v2 التاريخية (Issue #23). */
    private val PARITY_CONTACT_COLUMN_SPECS: Set<LegacyColumn> = setOf(
        LegacyColumn("lookupKey", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("accountType", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("accountName", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("fileAs", "TEXT", notNull = true, pkPosition = 0, defaultValue = "''"),
        LegacyColumn("prefix", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("middleName", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("suffix", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("nickname", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("phoneticName", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("department", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("notes", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("customRingtoneUri", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("sendToVoicemail", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        LegacyColumn("isBlocked", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        LegacyColumn("linkedServiceCloudId", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
        LegacyColumn("systemDeletedAt", "INTEGER", notNull = false, pkPosition = 0, defaultValue = null),
    )

    /** أعمدة contacts الهدف (29 عموداً بالمواصفات الفيزيائية الكاملة). */
    private val TARGET_CONTACT_COLUMN_SPECS: Set<LegacyColumn> =
        L0_CONTACT_COLUMN_SPECS + PARITY_CONTACT_COLUMN_SPECS

    /** أعمدة `contact_phones` غير المفتاح الأساسي (ثابتة عبر L0..PARITY_DONE). */
    private val CONTACT_PHONES_NON_PK_SPECS: Set<LegacyColumn> = setOf(
        LegacyColumn("contactId", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("raw", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("e164", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        LegacyColumn("type", "TEXT", notNull = true, pkPosition = 0, defaultValue = "'MOBILE'"),
        LegacyColumn("isPrimary", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
    )

    /**
     * المواصفات الكاملة لأعمدة الجداول الأساسية غير المتغيرة عبر L0..L3
     * (الاسم + النوع/الألفة + NOT NULL + موضع PK + القيمة الافتراضية).
     */
    private val INVARIANT_TABLE_COLUMN_SPECS: Map<String, Set<LegacyColumn>> = mapOf(
        "arham" to setOf(
            LegacyColumn("contactId", "INTEGER", notNull = true, pkPosition = 1, defaultValue = null),
            LegacyColumn("relation", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("layer", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("parentContactId", "INTEGER", notNull = false, pkPosition = 0, defaultValue = null),
            LegacyColumn("frequency", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("lastContactedAt", "INTEGER", notNull = false, pkPosition = 0, defaultValue = null),
            LegacyColumn("pausedUntil", "INTEGER", notNull = false, pkPosition = 0, defaultValue = null),
            LegacyColumn("createdAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        ),
        "services" to setOf(
            LegacyColumn("cloudId", "TEXT", notNull = true, pkPosition = 1, defaultValue = null),
            LegacyColumn("name", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("professionId", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("professionNameAr", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("professionNameEn", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("phoneE164", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("visibility", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("trustScore", "REAL", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("uniqueCallers", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("retentionRate", "REAL", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("ratingAvg", "REAL", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("ratingCount", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("isHot", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
            LegacyColumn("city", "TEXT", notNull = false, pkPosition = 0, defaultValue = null),
            LegacyColumn("ownerVerified", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
            LegacyColumn("isSavedByUser", "INTEGER", notNull = true, pkPosition = 0, defaultValue = "0"),
        ),
        "call_events" to setOf(
            LegacyColumn("callLogId", "TEXT", notNull = true, pkPosition = 1, defaultValue = null),
            LegacyColumn("e164", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("direction", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("durationSec", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("timestamp", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("source", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
        ),
        "arham_inbox" to setOf(
            LegacyColumn("id", "INTEGER", notNull = true, pkPosition = 1, defaultValue = null),
            LegacyColumn("senderUid", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("senderName", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("recipientUid", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("messageText", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("direction", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("status", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("sentAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
        ),
        "dispute_logs" to setOf(
            LegacyColumn("id", "INTEGER", notNull = true, pkPosition = 1, defaultValue = null),
            LegacyColumn("serviceCloudId", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("description", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("status", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("createdAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("updatedAt", "INTEGER", notNull = true, pkPosition = 0, defaultValue = null),
            LegacyColumn("resolvedAt", "INTEGER", notNull = false, pkPosition = 0, defaultValue = null),
        ),
    )

    private val CHECKPOINTS_COLUMN_SPECS: Set<LegacyColumn> = setOf(
        LegacyColumn("key", "TEXT", notNull = true, pkPosition = 1, defaultValue = null),
        LegacyColumn("value", "TEXT", notNull = true, pkPosition = 0, defaultValue = null),
    )

    private val EXPECTED_PK_COLUMN_BY_TABLE: Map<String, String> = mapOf(
        "contacts" to "id",
        "contact_phones" to "id",
        "arham" to "contactId",
        "services" to "cloudId",
        "call_events" to "callLogId",
        "arham_inbox" to "id",
        "dispute_logs" to "id",
        "contacts_sync_checkpoints" to "key",
    )

    private val INVARIANT_TABLE_INDEX_SPECS: Map<String, Set<LegacyIndex>> = mapOf(
        "contact_phones" to setOf(
            LegacyIndex("idx_contact_phones_cid", unique = false, origin = "c", partial = false, columns = listOf("contactId"), orders = listOf("ASC")),
            LegacyIndex("idx_contact_phones_e164", unique = false, origin = "c", partial = false, columns = listOf("e164"), orders = listOf("ASC")),
        ),
        "arham" to emptySet(),
        "services" to setOf(
            LegacyIndex("idx_services_phone", unique = false, origin = "c", partial = false, columns = listOf("phoneE164"), orders = listOf("ASC")),
            LegacyIndex("idx_services_prof", unique = false, origin = "c", partial = false, columns = listOf("professionId"), orders = listOf("ASC")),
        ),
        "call_events" to setOf(
            LegacyIndex("idx_call_events_ts", unique = false, origin = "c", partial = false, columns = listOf("timestamp"), orders = listOf("ASC")),
        ),
        "arham_inbox" to setOf(
            LegacyIndex("idx_arham_inbox_sent", unique = false, origin = "c", partial = false, columns = listOf("sentAt"), orders = listOf("ASC")),
        ),
        "dispute_logs" to emptySet(),
        "contacts_sync_checkpoints" to emptySet(),
    )

    private val BASE_CONTACT_INDEX_SPECS: Set<LegacyIndex> = setOf(
        LegacyIndex("idx_contacts_category", unique = false, origin = "c", partial = false, columns = listOf("category"), orders = listOf("ASC")),
        LegacyIndex("idx_contacts_norm_name", unique = false, origin = "c", partial = false, columns = listOf("normalizedName"), orders = listOf("ASC")),
        LegacyIndex("idx_contacts_fav", unique = false, origin = "c", partial = false, columns = listOf("isFavorite"), orders = listOf("ASC")),
    )

    private val TARGET_SYSTEM_CONTACT_ID_INDEX_SPEC = LegacyIndex(
        name = "idx_contacts_system_id",
        unique = false,
        origin = "c",
        partial = false,
        columns = listOf("systemContactId"),
        orders = listOf("ASC"),
    )

    private val FTS5_COLUMNS = listOf(
        "displayName", "nickname", "company", "notes", "normalizedName",
    )

    private val FTS6_COLUMNS = listOf(
        "displayName", "nickname", "company", "department", "notes", "normalizedName",
    )

    /** يقرأ فهارس جدول واحد مع البيانات الوصفية الكاملة (`origin`, `partial`, الترتيب، وفحص التعبيرات). */
    internal suspend fun readIndicesForTable(
        connection: SQLiteConnection,
        table: String,
    ): List<LegacyIndex> {
        val escapedTable = table.replace("\"", "\"\"")
        return connection.prepare("PRAGMA index_list(\"$escapedTable\")").use { listStmt ->
            val rawIndices = mutableListOf<Triple<String, Boolean, Pair<String, Boolean>>>()
            while (listStmt.step()) {
                val indexName = listStmt.getText(1)
                val isUnique = listStmt.getLong(2) != 0L
                val origin = if (listStmt.isNull(3)) "" else listStmt.getText(3)
                val isPartial = listStmt.getLong(4) != 0L
                rawIndices += Triple(indexName, isUnique, origin to isPartial)
            }
            rawIndices.map { (indexName, isUnique, originAndPartial) ->
                val (origin, isPartial) = originAndPartial
                val cols = mutableListOf<String>()
                val orders = mutableListOf<String>()
                var hasExpression = false
                val escapedIndexName = indexName.replace("\"", "\"\"")
                connection.prepare("PRAGMA index_xinfo(\"$escapedIndexName\")").use { infoStmt ->
                    while (infoStmt.step()) {
                        val isKeyColumn = infoStmt.getLong(5) != 0L
                        if (!isKeyColumn) continue
                        val cid = infoStmt.getLong(1).toInt()
                        if (cid < 0 || infoStmt.isNull(2)) {
                            hasExpression = true
                        } else {
                            cols += infoStmt.getText(2)
                        }
                        val isDesc = infoStmt.getLong(3) != 0L
                        orders += if (isDesc) "DESC" else "ASC"
                    }
                }
                LegacyIndex(
                    name = indexName,
                    unique = isUnique,
                    origin = origin,
                    partial = isPartial,
                    columns = cols,
                    orders = orders,
                    hasExpression = hasExpression,
                )
            }
        }
    }

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
            val escapedTable = table.replace("\"", "\"\"")
            connection.prepare("PRAGMA table_info(\"$escapedTable\")").use { stmt ->
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
            readIndicesForTable(connection, table)
        }

        val foreignKeyCountsByTable = userTables.associateWith { table ->
            val escapedTable = table.replace("\"", "\"\"")
            connection.prepare("PRAGMA foreign_key_list(\"$escapedTable\")").use { stmt ->
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
     * لا يُصنَّف إلا المخطط التاريخي المُثبت حرفياً؛ أي انحراف يُرجع `UNKNOWN` دون تعديل.
     */
    fun classify(snapshot: LegacySchemaSnapshot): LegacySchemaLevel {
        if (snapshot.userVersion != 0) return ROOM_MANAGED

        val tables = snapshot.tableNames.toSet()
        // تثبيت جديد فارغ — بلا جداول مستخدم إطلاقاً.
        if (tables.isEmpty()) return LegacySchemaLevel.FRESH

        // جداول الظل القياسية لـ FTS4 مسموحة فقط عند وجود جدول contacts_fts الفعلي.
        val allowedFtsShadowTables = if ("contacts_fts" in tables) FTS_SHADOW_TABLES else emptySet()
        val unexpectedTables = tables - TARGET_TABLES - allowedFtsShadowTables

        // قيود رفض صارمة قبل التصنيف: مشغّلات أو قيود FK أو جداول غريبة أو نواقص أساسية ⇒ UNKNOWN.
        if (snapshot.triggerNames.isNotEmpty()) return UNKNOWN
        if (snapshot.foreignKeyCountsByTable.values.any { it > 0 }) return UNKNOWN
        if (unexpectedTables.isNotEmpty()) return UNKNOWN
        if ((CORE_TABLES - tables).isNotEmpty()) return UNKNOWN

        // التحقق الدقيق من أعمدة وفهارس الجداول الأساسية ونقاط الاستئناف (إن وُجدت):
        if (!hasExpectedInvariantColumns(snapshot, tables)) return UNKNOWN
        if (!hasExpectedInvariantIndices(snapshot, tables)) return UNKNOWN

        val hasCheckpoints = "contacts_sync_checkpoints" in tables
        val hasFts = snapshot.ftsColumns != null

        // SR-02 (§4.3): FTS ونقاط الاستئناف أُدخلا معاً تاريخياً — وجود أحدهما دون الآخر ⇒ UNKNOWN.
        if (hasFts != hasCheckpoints) return UNKNOWN

        val contactsColumns = snapshot.columnsByTable["contacts"]
            ?.map { it.normalized() }
            ?.toSet() ?: return UNKNOWN

        if (!hasFts && !hasCheckpoints) {
            if (contactsColumns != L0_CONTACT_COLUMN_SPECS) return UNKNOWN
            if (!hasValidL0ContactsIndices(snapshot)) return UNKNOWN
            return L0
        }

        // في L1 وL2/L3 وPARITY_DONE يجب أن تطابق أعمدة contacts الـ 29 عموداً الهدف حرفياً.
        if (contactsColumns != TARGET_CONTACT_COLUMN_SPECS) return UNKNOWN
        if (!hasValidV2ContactsIndices(snapshot)) return UNKNOWN

        if (ftsShapeIsTarget(snapshot) &&
            hasTargetContactsIndices(snapshot) &&
            phonesPkDeclaresNotNull(snapshot)
        ) {
            return PARITY_DONE
        }

        return when {
            ftsShapeIsTarget(snapshot) -> L2_OR_L3
            ftsShapeIsL1OrForeignOptionsTarget(snapshot) -> L1
            else -> UNKNOWN
        }
    }

    private fun hasExpectedInvariantColumns(snapshot: LegacySchemaSnapshot, tables: Set<String>): Boolean {
        val phonesColumns = snapshot.columnsByTable["contact_phones"]
            ?.map { it.normalized() } ?: return false
        if (phonesColumns.size != CONTACT_PHONES_NON_PK_SPECS.size + 1) return false
        val phonesPk = phonesColumns.firstOrNull { it.name == "id" } ?: return false
        if (phonesPk.type != "INTEGER" || phonesPk.pkPosition != 1 || phonesPk.defaultValue != null) {
            return false
        }
        val phonesNonPk = phonesColumns.filterNot { it.name == "id" }.toSet()
        if (phonesNonPk != CONTACT_PHONES_NON_PK_SPECS) return false

        for ((table, expectedSpecs) in INVARIANT_TABLE_COLUMN_SPECS) {
            val actualList = snapshot.columnsByTable[table]?.map { it.normalized() } ?: return false
            if (actualList.size != expectedSpecs.size || actualList.toSet() != expectedSpecs) {
                return false
            }
        }
        if ("contacts_sync_checkpoints" in tables) {
            val actualCheckpoints = snapshot.columnsByTable["contacts_sync_checkpoints"]
                ?.map { it.normalized() } ?: return false
            if (actualCheckpoints.size != CHECKPOINTS_COLUMN_SPECS.size ||
                actualCheckpoints.toSet() != CHECKPOINTS_COLUMN_SPECS
            ) {
                return false
            }
        }
        return true
    }

    private fun hasExpectedInvariantIndices(snapshot: LegacySchemaSnapshot, tables: Set<String>): Boolean {
        for ((table, expectedExplicitIndices) in INVARIANT_TABLE_INDEX_SPECS) {
            if (table !in tables) continue
            val actualIndices = snapshot.indicesByTable[table].orEmpty()
            if (!validateTableIndices(table, actualIndices, expectedExplicitIndices)) {
                return false
            }
        }
        return true
    }

    private fun validateTableIndices(
        table: String,
        actualIndices: List<LegacyIndex>,
        expectedExplicitIndices: Set<LegacyIndex>,
    ): Boolean {
        val expectedPkColumn = EXPECTED_PK_COLUMN_BY_TABLE[table] ?: return false
        val explicitIndices = mutableListOf<LegacyIndex>()
        for (idx in actualIndices) {
            if (idx.hasExpression || idx.columns.isEmpty()) return false
            when (idx.origin) {
                "pk" -> {
                    if (!idx.unique || idx.partial ||
                        idx.columns != listOf(expectedPkColumn) ||
                        idx.orders != listOf("ASC")
                    ) {
                        return false
                    }
                }
                "c" -> explicitIndices += idx
                else -> return false // يرفض origin="u" (UNIQUE قيد جدول إضافي) وأي أصل غير معروف.
            }
        }
        return explicitIndices.size == expectedExplicitIndices.size &&
            explicitIndices.toSet() == expectedExplicitIndices
    }

    private fun hasValidL0ContactsIndices(snapshot: LegacySchemaSnapshot): Boolean =
        validateContactsIndices(snapshot, requireSystemIdIndex = false, requireNonUniqueSystemId = false)

    private fun hasValidV2ContactsIndices(snapshot: LegacySchemaSnapshot): Boolean =
        validateContactsIndices(snapshot, requireSystemIdIndex = true, requireNonUniqueSystemId = false)

    private fun hasTargetContactsIndices(snapshot: LegacySchemaSnapshot): Boolean =
        validateContactsIndices(snapshot, requireSystemIdIndex = true, requireNonUniqueSystemId = true)

    private fun validateContactsIndices(
        snapshot: LegacySchemaSnapshot,
        requireSystemIdIndex: Boolean,
        requireNonUniqueSystemId: Boolean,
    ): Boolean {
        val actualIndices = snapshot.indicesByTable["contacts"].orEmpty()
        val explicitIndices = mutableListOf<LegacyIndex>()
        for (idx in actualIndices) {
            if (idx.hasExpression || idx.columns.isEmpty()) return false
            when (idx.origin) {
                "pk" -> {
                    if (!idx.unique || idx.partial ||
                        idx.columns != listOf("id") ||
                        idx.orders != listOf("ASC")
                    ) {
                        return false
                    }
                }
                "c" -> explicitIndices += idx
                else -> return false
            }
        }

        val systemIdIndices = explicitIndices.filter { it.columns == listOf("systemContactId") }
        val otherExplicit = explicitIndices.filterNot { it.columns == listOf("systemContactId") }

        if (otherExplicit.size != BASE_CONTACT_INDEX_SPECS.size ||
            otherExplicit.toSet() != BASE_CONTACT_INDEX_SPECS
        ) {
            return false
        }

        if (systemIdIndices.size > 1) return false
        if (requireSystemIdIndex && systemIdIndices.isEmpty()) return false

        val sysIdx = systemIdIndices.firstOrNull() ?: return true
        if (sysIdx.name != TARGET_SYSTEM_CONTACT_ID_INDEX_SPEC.name ||
            sysIdx.partial ||
            sysIdx.hasExpression ||
            sysIdx.orders != listOf("ASC")
        ) {
            return false
        }
        if (requireNonUniqueSystemId) {
            return sysIdx == TARGET_SYSTEM_CONTACT_ID_INDEX_SPEC
        }
        return true
    }

    /** شكل FTS الهدف: أعمدة الستّة **وخيارات التعريف مطابقة للثابت الموحد** (فارغة — SIMPLE). */
    private fun ftsShapeIsTarget(snapshot: LegacySchemaSnapshot): Boolean =
        snapshot.ftsColumns == FTS6_COLUMNS && snapshot.ftsOptions == LegacyFtsDdl.TARGET_OPTIONS

    /** شكل FTS في L1 (5 أعمدة معيارية) أو 6 أعمدة بخيارات تعريف أجنبية تحتاج إعادة بناء. */
    private fun ftsShapeIsL1OrForeignOptionsTarget(snapshot: LegacySchemaSnapshot): Boolean =
        (snapshot.ftsColumns == FTS5_COLUMNS && snapshot.ftsOptions == LegacyFtsDdl.TARGET_OPTIONS) ||
            (snapshot.ftsColumns == FTS6_COLUMNS && snapshot.ftsOptions != LegacyFtsDdl.TARGET_OPTIONS)

    /** `contact_phones` يعلن NOT NULL في PK؟ (شرط مطابقة Room — أُثبت بالتجربة). */
    private fun phonesPkDeclaresNotNull(snapshot: LegacySchemaSnapshot): Boolean =
        snapshot.columnsByTable["contact_phones"]
            ?.firstOrNull { it.pkPosition > 0 }
            ?.notNull == true
}
