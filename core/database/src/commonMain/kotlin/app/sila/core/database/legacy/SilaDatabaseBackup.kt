package app.sila.core.database.legacy

import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.open
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * فشل إنشاء النسخة الاحتياطية الواقية أو التحقق منها — فشل آمن (لا اعتماد ولا فتح).
 */
internal class LegacyBackupException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * النسخة الاحتياطية الواقية **لمرة واحدة** قبل أول اعتماد لمخطط Room (ADR-0011 §النسخ الاحتياطي).
 *
 * القواعد غير القابلة للكسر:
 * - **لا نسخ خام للملفات إطلاقاً** — تحت WAL قد يكون ملف `.db` وحده غير مكتمل؛ `VACUUM INTO`
 *   يقرأ الحالة المنطقية الكاملة للقاعدة (شاملاً WAL) ويكتب نسخة متسقة ذاتية الاحتواء.
 * - فحص المساحة المتاحة **أولاً**: المطلوب 2× الحجم المنطقي للقاعدة + هامش 16MB — وإن تعذّر
 *   تحديد المساحة فيُعامَل بخفض الأمان (فشل).
 * - **التحقق من النسخة**: `PRAGMA integrity_check` كاملة (كل الصفوف = ok) + مطابقة عدد صفوف
 *   كل جدول منطقي بين الأصل والنسخة. نسخة غير متحقق منها لا تُعتمد ابداً كحماية.
 * - النسخة **مستثناة من النسخ الاحتياطي السحابي** (F-06 — backup_rules.xml/data_extraction_rules)
 *   لأنها نسخة طبق الأصل من بيانات جهات اتصال خاصة.
 * - سياسة «نسخة متحقق منها موجودة مسبقاً» ⇒ لا تُعاد النسخ (الأمان أولاً — إعادة VACUUM INTO
 *   على قاعدة أثناء انقطاع قد تُدخل حالة غير متسقة؛ النسخة القائمة الصالحة تكفي كحماية).
 */
internal object SilaDatabaseBackup {

    /** اسم ملف النسخة الواقية — شقيق لـ `sila_local.db` في نفس مجلد التطبيق الخاص. */
    const val BACKUP_FILE_NAME = "sila_local.pre-room-backup"

    private const val EXTRA_MARGIN_BYTES: Long = 16L * 1024 * 1024

    /** مسار النسخة الواقية لقاعدة في [dbFilePath] (نفس المجلد) — يقبل فاصلَي `/` و`\` معاً. */
    fun backupPathFor(dbFilePath: String): String {
        val dir = directoryOf(dbFilePath)
        return if (dir.isEmpty()) BACKUP_FILE_NAME else "$dir/$BACKUP_FILE_NAME"
    }

    /** مجلد احتواء [dbFilePath] — فاصلَا `/` و`\` معاً (توحيد مع [backupPathFor] — تحصين مراجعة GLM). */
    private fun directoryOf(dbFilePath: String): String {
        val lastSeparator = maxOf(dbFilePath.lastIndexOf('/'), dbFilePath.lastIndexOf('\\'))
        return if (lastSeparator < 0) "" else dbFilePath.substring(0, lastSeparator)
    }

    /**
     * هل توجد نسخة واقية سابقة **متحقق منها** (غير فارغة + تحتوي جدول contacts + integrity_check = ok)؟
     * وجود ملف غير صالح أو بحجم صفر لا يرمي — يُرجع false ليعاد إنشاؤه أو يُرفض في الاستئناف.
     */
    suspend fun verifiedBackupExists(dbFilePath: String): Boolean {
        val path = backupPathFor(dbFilePath)
        if (!PlatformFileOps.fileExists(path)) return false
        val sizeBytes = PlatformFileOps.fileSizeBytes(path) ?: return false
        if (sizeBytes <= 0L) return false
        return try {
            BundledSQLiteDriver().open(path).use { conn ->
                val hasContactsTable = conn.prepare(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'contacts'",
                ).use { stmt ->
                    stmt.step() && stmt.getLong(0) > 0L
                }
                hasContactsTable && isFullIntegrityOk(conn)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * ينشئ نسخة واقية موثقة من قاعدة [dbFilePath]: فحص مساحة → `VACUUM INTO` → تحقق
     * (integrity_check كاملة + مطابقة عدد الصفوف لكل جدول منطقي).
     * ترمي [LegacyBackupException] عند أي فشل — **لا يُمس ملف القاعدة الأصلي**.
     */
    suspend fun createVerifiedBackup(dbFilePath: String) {
        val backupPath = backupPathFor(dbFilePath)

        // 1) فحص المساحة أولاً (fail-closed عند تعذر التحديد).
        val directory = directoryOf(dbFilePath)
        val freeBytes = PlatformFileOps.freeSpaceBytes(directory)
        val sourceLogicalBytes = runCatching {
            BundledSQLiteDriver().open(dbFilePath).use { conn -> readLogicalSizeBytes(conn) }
        }.getOrDefault(0L)
        val requiredBytes = 2L * sourceLogicalBytes + EXTRA_MARGIN_BYTES
        if (freeBytes == null || freeBytes < requiredBytes) {
            throw LegacyBackupException(
                "PR_B backup: insufficient storage — required=$requiredBytes" +
                    " available=${freeBytes ?: "unknown"} (dbLogical=$sourceLogicalBytes)",
            )
        }

        // 2) حذف مخلفات نسخة قديمة تالفة — VACUUM INTO يتطلب هدفاً غير موجود.
        PlatformFileOps.deleteFileIfExists(backupPath)

        // 3) النسخ: VACUUM INTO — لا يجوز داخل معاملة، ويقرأ الحالة المنطقية الكاملة (incl. WAL).
        val countsByTable = mutableListOf<Pair<String, Long>>()
        BundledSQLiteDriver().open(dbFilePath).use { source ->
            countsByTable += readLogicalTableRowCounts(source)
            val escapedPath = backupPath.replace("'", "''")
            source.executeSQL("VACUUM INTO '$escapedPath'")
        }

        // 4) تحقق: سلامة كاملة + تطابق عدد الصفوف.
        BundledSQLiteDriver().open(backupPath).use { copy ->
            if (!isFullIntegrityOk(copy)) {
                PlatformFileOps.deleteFileIfExists(backupPath)
                throw LegacyBackupException(
                    "PR_B backup: integrity_check failed for freshly created backup",
                )
            }
            val copyCounts = readLogicalTableRowCounts(copy).toMap()
            for ((table, expected) in countsByTable) {
                if (copyCounts[table] != expected) {
                    PlatformFileOps.deleteFileIfExists(backupPath)
                    throw LegacyBackupException(
                        "PR_B backup: row-count mismatch after VACUUM INTO — table=$table" +
                            " expected=$expected actual=${copyCounts[table]}",
                    )
                }
            }
        }
    }

    /** حذف النسخة الواقية بعد اعتماد موثق (خلية أمان رجعية — حذف صريح فقط). */
    fun deleteBackupIfExists(dbFilePath: String): Boolean =
        PlatformFileOps.deleteFileIfExists(backupPathFor(dbFilePath))

    /** الحجم المنطقي للقاعدة (page_count × page_size) — عبر اتصال مفتوح. */
    private suspend fun readLogicalSizeBytes(conn: androidx.sqlite.SQLiteConnection): Long =
        conn.prepare("PRAGMA page_count").use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        } * conn.prepare("PRAGMA page_size").use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }

    /**
     * عدد صفوف كل **جدول منطقي** مستخدم (بلا جداول sqlite_ الداخلية وبلا جداول ظل FTS —
     * وهي مشتقة حصراً ويتحقق محتواها ضمن integrity_check).
     */
    private suspend fun readLogicalTableRowCounts(
        conn: androidx.sqlite.SQLiteConnection,
    ): List<Pair<String, Long>> {
        val tables = mutableListOf<String>()
        conn.prepare(
            "SELECT name FROM sqlite_master WHERE type = 'table'" +
                " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'" +
                " AND name NOT LIKE 'contacts\\_fts\\_%' ESCAPE '\\'",
        ).use { stmt ->
            while (stmt.step()) tables += stmt.getText(0)
        }
        return tables.map { table ->
            val count = conn.prepare("SELECT COUNT(*) FROM \"$table\"").use { stmt ->
                if (stmt.step()) stmt.getLong(0) else -1L
            }
            table to count
        }
    }

    /** `PRAGMA integrity_check` كاملة: صف واحد على الأقل وكل الصفوف يجب أن تكون ok. */
    private suspend fun isFullIntegrityOk(conn: androidx.sqlite.SQLiteConnection): Boolean =
        conn.prepare("PRAGMA integrity_check").use { stmt ->
            var rowCount = 0
            var allOk = true
            while (stmt.step()) {
                rowCount++
                if (stmt.isNull(0) || stmt.getText(0) != "ok") allOk = false
            }
            rowCount > 0 && allOk
        }
}
