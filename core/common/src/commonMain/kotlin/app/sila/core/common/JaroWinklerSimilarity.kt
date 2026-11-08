package app.sila.core.common

/**
 * تشابه Jaro-Winkler الخالص (T7 — Issue #28، spec 04 §أ: «JaroWinkler(name) ≥ 0.92 ← LOW اقتراح
 * فقط»). تُدفع أسماء **مطبَّعة للمقارنة** ([ArabicTextNormalizer.normalizeForComparison]) حصراً —
 * هذه الدالة نقية بلا أي تطبيع ذاتي حتى يبقى العقد صريحاً.
 *
 * التطبيق القياسي: Jaro ببيزة مطابقة `floor(max(|a|,|b|)/2) - 1` (الأرضي 0)، انتقالات نصف-عدد،
 * ثم توسعة Winkler لبادئة حتى 4 أحرف بمعامل 0.1 وعتبة تعزيز 0.7 (المواصفة القياسية المرجعية —
 * نفس متجهات الاختبار المتعارَف عليها MARTHA/MARHTA وDWAYNE/DUANE).
 *
 * ملاحظة تصميمية (agy F7): المقارنة الكاملة للأسماء المركّبة العربية الباترونيمية
 * («عبدالرحمن محمد»/«عبدالرحمن أحمد» ≈ 0.943) تُحكم **خارج** هذه الدالة بقاعدة الرمز-الأخير في
 * مسار المرشّحين — هنا تُقدَّم الدقة الحسابية القياسية فقط.
 */
object JaroWinklerSimilarity {

    private const val PREFIX_SCALE = 0.1
    private const val MAX_PREFIX = 4
    private const val BOOST_THRESHOLD = 0.7

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val jaro = jaro(a, b)
        if (jaro <= BOOST_THRESHOLD) return jaro

        var prefix = 0
        val maxPrefix = minOf(MAX_PREFIX, a.length, b.length)
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro)
    }

    private fun jaro(a: String, b: String): Double {
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)

        var matches = 0
        for (i in a.indices) {
            val from = maxOf(0, i - window)
            val to = minOf(b.length - 1, i + window)
            for (j in from..to) {
                if (!bMatches[j] && a[i] == b[j]) {
                    aMatches[i] = true
                    bMatches[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var bIndex = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[bIndex]) bIndex++
            if (a[i] != b[bIndex]) transpositions++
            bIndex++
        }
        val t = transpositions / 2.0
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t) / m) / 3.0
    }
}
