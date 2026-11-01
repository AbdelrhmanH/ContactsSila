package app.sila.core.database.legacy

/**
 * عمليات ملفات منصة محدودة يحتاجها طبق النسخ الاحتياطي/الاعتماد (ADR-0011 §النسخ الاحتياطي).
 *
 * نطاقها **حصرياً** ملفات قاعدة البيانات داخل مساحة التطبيق الخاصة — بلا أي وصول
 * لملفات عامة أو شبكة أو سجلات. `null` يعني «تعذر التحديد» فيُعامَل بخفض الأمان
 * (fail-closed) في نقاط الفحص الخاصة بالمساحة المتاحة.
 */
internal expect object PlatformFileOps {

    fun fileExists(path: String): Boolean

    fun fileSizeBytes(path: String): Long?

    /** المساحة المتاحة في وحدة التخزين التي يحتضن [directoryPath] — `null` إن تعذر التحديد. */
    fun freeSpaceBytes(directoryPath: String): Long?

    /** حذف ملف واحد إن وُجد — يُرجع true إذا اختفى الملف بعد الاستدعاء. */
    fun deleteFileIfExists(path: String): Boolean

    /** مسار مطلق قابل للكتابة لملف قاعدة اختبار مؤقتة ([fileName]) على المنصة الحالية. */
    fun tempTestDbPath(fileName: String): String
}
