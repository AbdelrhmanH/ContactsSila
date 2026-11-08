package app.sila.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * عقود المقارنة الاسمية لـ T7 (Issue #28): إزالة اللقب عند المقارنة، التوكنة الرمزية، وعتبة
 * Jaro-Winkler. كلها دوال نقية — لا تخزين ولا بحث.
 */
class NameComparisonTest {

    // ——— normalizeForComparison: إزالة اللقب المقارَن (spec 04 §أ: د.، م.، أ.، Dr) ———

    @Test
    fun comparisonStripsDocumentedTitles_repeatedly() {
        assertEquals("احمد", ArabicTextNormalizer.normalizeForComparison("د. أحمد"))
        assertEquals("احمد", ArabicTextNormalizer.normalizeForComparison("د . أحمد".replace(" . ", ". ")))
        assertEquals("احمد", ArabicTextNormalizer.normalizeForComparison("م. أحمد"))
        assertEquals("حسن", ArabicTextNormalizer.normalizeForComparison("أ. حسن"))
        assertEquals("ahmed", ArabicTextNormalizer.normalizeForComparison("Dr. Ahmed"))
        assertEquals("ahmed", ArabicTextNormalizer.normalizeForComparison("dr ahmed"))
        // الاستبعاد متكرر: «د. د. أحمد» يُزاح لقبان
        assertEquals("احمد", ArabicTextNormalizer.normalizeForComparison("د. د. أحمد"))
    }

    @Test
    fun comparisonPreservesNonTitles_andNeverEmptiesTheName() {
        // الأسماء التي تبدأ بألف/ميم عادية لا تُمس (الإزاحة على الشكل الخام قبل التطبيع)
        assertEquals("احمد", ArabicTextNormalizer.normalizeForComparison("أحمد"))
        assertEquals("محمد", ArabicTextNormalizer.normalizeForComparison("محمد"))
        assertEquals("مريم", ArabicTextNormalizer.normalizeForComparison("مريم"))
        // الاسم اللقبي وحده لا يُفرَّغ
        assertEquals("د.", ArabicTextNormalizer.normalizeForComparison("د."))
        assertEquals("", ArabicTextNormalizer.normalizeForComparison(""))
    }

    // ——— ArabicTokenizer: مطابقة رمزية حصراً ———

    @Test
    fun tokenizerSplitsWords_andStripsOnlySafeClitics() {
        assertEquals(listOf("محمد", "دكتوري"), ArabicTokenizer.split("محمد دكتوري"))
        assertEquals(listOf("عمي"), ArabicTokenizer.split("والعمي"))
        assertEquals(listOf("كتاب"), ArabicTokenizer.split("الكتاب"))
        assertEquals(listOf("عمي", "حسن"), ArabicTokenizer.split("العمي وحسن"))
    }

    @Test
    fun tokenizerNeverProducesPartialMatches_documentedNegativeCase() {
        // الحالة السلبية الموثقة في TESTING_STRATEGY: «محمد دكتوري» لا تختلط بـ«دكتور»
        val tokens = ArabicTokenizer.split(ArabicTextNormalizer.normalize("محمد دكتوري"))
        assertFalse(tokens.any { token -> ArabicTokenizer.tokensMatch(token, "دكتور") })
        // «العمل» ليست «العم» (الاقتطاع الصغرى: ال يُقتطع فتبقى «عمل» ≠ «عم»)
        val work = ArabicTokenizer.split(ArabicTextNormalizer.normalize("العمل"))
        assertFalse(work.any { it == "عم" })
        // ولا يُقتطع الكاف/اللام/الباء — «كريم» تبقى «كريم»
        assertTrue(ArabicTokenizer.split("كريم").contains("كريم"))
    }

    // ——— JaroWinklerSimilarity: متجهات قياسية + العتبة ———

    @Test
    fun jaroWinklerMatchesStandardReferenceVectors() {
        assertEquals(1.0, JaroWinklerSimilarity.similarity("DIXON", "DIXON"))
        assertEquals(0.961, JaroWinklerSimilarity.similarity("MARTHA", "MARHTA"), 0.001)
        assertEquals(0.840, JaroWinklerSimilarity.similarity("DWAYNE", "DUANE"), 0.001)
        assertEquals(0.813, JaroWinklerSimilarity.similarity("DIXON", "DICKSONX"), 0.001)
        assertEquals(0.0, JaroWinklerSimilarity.similarity("", "احمد"))
        assertEquals(1.0, JaroWinklerSimilarity.similarity("", ""))
    }

    @Test
    fun jaroWinklerBoundary_at092AndArabicVariants() {
        // متغيرات الإملاء العربي للتسمية نفسها فوق العتبة (اقتراح LOW)
        assertTrue(
            JaroWinklerSimilarity.similarity(
                ArabicTextNormalizer.normalizeForComparison("أحمد إبراهيم"),
                ArabicTextNormalizer.normalizeForComparison("احمد ابراهيم"),
            ) >= 0.92,
        )
        // الباترونيمي المزيف (agy F7): قريب من العتبة لكن قراره في مسار المرشحين (الرمز الأخير)
        val patronymic = JaroWinklerSimilarity.similarity(
            ArabicTextNormalizer.normalizeForComparison("عبدالرحمن محمد"),
            ArabicTextNormalizer.normalizeForComparison("عبدالرحمن أحمد"),
        )
        assertTrue(patronymic in 0.90..0.99, "القيمة المرجعية ≈0.943 — الدالة الحسابية قياسية")
    }
}
