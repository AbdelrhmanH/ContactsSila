package app.sila.core.common

/**
 * توكنة أسماء عربية للمطابقة على مستوى الرموز (T7 — Issue #28، spec 04 §ب:
 * «المطابقة على مستوى الرموز (tokens) لا الأجزاء، لتجنب "محمد" ← "حمد"»).
 *
 * الإدخال متوقع مطبَّعاً بـ [ArabicTextNormalizer.normalize] أولاً (العرف الموثق: مصطلحات
 * القاموس تُكتب بصيغة ما-بعد-التطبيع).
 *
 * سياسة الاقتطاع **الصغرى الآمنة**: اقتطاع أدوات التعريف والوصل فقط:
 * - «ال» التعريف («الكتاب» ← «كتاب»، «والعمي» ← «عمي» بعد الوصل).
 * - واو العطف «و» المتصلة («وخالتي» ← «خالتي»).
 *
 * **عمداً لا يُقتطع** ب/ل/ف/ك — فاقتطاعها يولّد إنكارات زائفة («كريم» ← «ريم»،
 * «كتاب» ← «تاب») تفسد المطابقة. نطاق المطابقة رمزيّ كامل حصراً: لا substring.
 */
object ArabicTokenizer {

    fun split(normalized: String): List<String> =
        normalized.split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }
            .map { stripClitics(it) }

    /** يُستعمل عند مطابقة رمز قاموس مفرد ضد رمز اسم (مستوى-رمز حصراً — لا substring). */
    fun tokensMatch(nameToken: String, term: String): Boolean =
        nameToken == term || nameToken == stripClitics(term)

    private fun stripClitics(token: String): String {
        var t = token
        if (t.length > 2 && t.startsWith("و")) t = t.substring(1)
        if (t.length > 2 && t.startsWith("ال")) t = t.substring(2)
        return t
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
