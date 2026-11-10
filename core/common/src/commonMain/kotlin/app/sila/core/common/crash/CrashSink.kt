package app.sila.core.common.crash

/**
 * منفذ الكتابة المنصوي لتقارير الأعطال (ADR-0019): كل منصة تكتب ملف العطل في
 * تخزينها الخاص حصراً (Android: `filesDir/crash/`، iOS: `Application Support/Sila/crash/`)
 * — **لا شبكة ولا SDK سحابي إطلاقاً** (قرار المالك 2026-11-10).
 *
 * يُستدعى من [LocalCrashCapture] على مسار العطل الطرفي: أي إخفاق (رمي أو false)
 * يُلتقط هناك ويُفسَّر fail-closed — لا يُسمح له أبداً بإفساد مسار الانهيار نفسه.
 */
fun interface CrashSink {

    /**
     * يكتب محتوى التقرير المنقّى في ملف باسم مشتق من [timestampMillis]
     * (ضمان تفرد الأسماء وترتيب زمني معجمي مستقر لسياسة الاحتفاظ).
     * @return true عند نجاح الكتابة، false عند أي إخفاق.
     */
    fun write(timestampMillis: Long, content: String): Boolean
}
