package app.sila

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * اختبار تحقق تهيئة النسخ الاحتياطي واستخراج البيانات (F-06).
 *
 * سياسة الخصوصية (SECURITY_PRIVACY.md): قاعدة بيانات Room المحلية (sila_local.db)
 * وملفات WAL/SHM المرافقة وتفضيلات DataStore (sila_user_preferences.preferences_pb)
 * **لا تغادر الجهاز أبداً** — مستثناة من النسخ الاحتياطي السحابي (Auto Backup) ومن
 * نقل البيانات بين الأجهزة، على كل من:
 * - `android:fullBackupContent` (API 26–30) → `res/xml/backup_rules.xml`.
 * - `android:dataExtractionRules` (API 31+) → `res/xml/data_extraction_rules.xml`.
 *
 * يتحقق هنا من **الملفات الفعلية** التي يشير إليها الـ Manifest (وليس نصوصاً منسوخة)،
 * فأي انحراف بين الـ Manifest وقواعد الاستثناء يُفشل الاختبار مباشرة.
 */
class BackupConfigVerificationTest {

    // الملفات المحمية بسياسة الخصوصية — التخزين الفعلي في filesDir الجذر (KoinModule.kt).
    private val protectedFiles = listOf(
        "sila_local.db",
        "sila_local.db-wal",
        "sila_local.db-shm",
        "sila_user_preferences.preferences_pb"
    )

    // ===== أدوات =====

    /** مجلد موديول androidApp — من مجلد عمل الاختبار (الموديول نفسه) أو أي جذر أعلى. */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.name == "androidApp" && dir.resolve("src/main/AndroidManifest.xml").exists()) return dir
            val candidate = dir.resolve("androidApp")
            if (candidate.resolve("src/main/AndroidManifest.xml").exists()) return candidate
            dir = dir.parentFile
        }
        error("androidApp module not found from working dir ${System.getProperty("user.dir")}")
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    private fun manifest(): Document = parse(moduleDir().resolve("src/main/AndroidManifest.xml").path)

    private fun rulesDoc(fileName: String): Document = parse(moduleDir().resolve("src/main/res/xml/$fileName").path)

    private fun applicationElement(doc: Document): Element =
        doc.getElementsByTagName("application").item(0) as? Element
            ?: error("AndroidManifest.xml has no <application> element")

    /** مسارات الاستثناء (exclude) ضمن كل عنصر بالوسم المطلوب (cloud-backup / device-transfer / root). */
    private fun excludedPaths(doc: Document, sectionTag: String?): Set<String> {
        val roots = if (sectionTag == null) {
            listOf(doc.documentElement)
        } else {
            (0 until doc.getElementsByTagName(sectionTag).length)
                .map { doc.getElementsByTagName(sectionTag).item(it) as Element }
        }
        return roots.flatMap { section ->
            (0 until section.getElementsByTagName("exclude").length)
                .map { section.getElementsByTagName("exclude").item(it) as Element }
                .filter { it.getAttribute("domain") == "file" }
                .map { it.getAttribute("path") }
        }.toSet()
    }

    // ===== الـ Manifest =====

    @Test
    fun manifest_declaresBackupRules_forBothMechanisms() {
        val app = applicationElement(manifest())

        assertEquals("allowBackup يبقى مفعلاً — الاستبعاد عبر قواعد صريحة", "true", app.getAttribute("android:allowBackup"))
        assertEquals(
            "الـ Manifest يجب أن يشير إلى قواعد الاستخراج (API 31+)",
            "@xml/data_extraction_rules",
            app.getAttribute("android:dataExtractionRules")
        )
        assertEquals(
            "الـ Manifest يجب أن يشير إلى قواعد النسخ الاحتياطي القديمة (API 26–30)",
            "@xml/backup_rules",
            app.getAttribute("android:fullBackupContent")
        )
    }

    // ===== قواعد الاستخراج (API 31+: cloud-backup + device-transfer) =====

    @Test
    fun dataExtractionRules_excludeProtectedFiles_fromCloudBackup() {
        val doc = rulesDoc("data_extraction_rules.xml")
        val excluded = excludedPaths(doc, "cloud-backup")
        assertTrue("cloud-backup section must declare file exclusions", excluded.isNotEmpty())
        protectedFiles.forEach { file ->
            assertTrue("cloud-backup must exclude $file (found: $excluded)", file in excluded)
        }
    }

    @Test
    fun dataExtractionRules_excludeProtectedFiles_fromDeviceTransfer() {
        val doc = rulesDoc("data_extraction_rules.xml")
        val excluded = excludedPaths(doc, "device-transfer")
        assertTrue("device-transfer section must declare file exclusions", excluded.isNotEmpty())
        protectedFiles.forEach { file ->
            assertTrue("device-transfer must exclude $file (found: $excluded)", file in excluded)
        }
    }

    // ===== قواعد النسخ الاحتياطي القديمة (API 26–30: full-backup-content) =====

    @Test
    fun backupRules_excludeProtectedFiles_fromLegacyAutoBackup() {
        val doc = rulesDoc("backup_rules.xml")
        val excluded = excludedPaths(doc, null)
        protectedFiles.forEach { file ->
            assertTrue("full-backup-content must exclude $file (found: $excluded)", file in excluded)
        }
    }
}
