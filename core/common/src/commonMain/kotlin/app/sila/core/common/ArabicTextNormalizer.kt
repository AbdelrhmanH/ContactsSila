package app.sila.core.common

/**
 * تطبيع النص العربي للبحث الفوري (FTS) وكشف الأسماء المكررة (PRD §7).
 * - إزالة التشكيل (الحركات) والتطويل (الكشيدة).
 * - توحيد الهمزات (أ، إ، آ، ٱ -> ا).
 * - توحيد التاء المربوطة (ة -> ه) والألف المقصورة (ى -> ي).
 * - تحويل الأرقام العربية الشرقية (٠-٩) إلى أرقام غربية (0-9).
 * - إزالة المسافات الزائدة وتوحيد الأحرف اللاتينية إلى صغيرة.
 */
object ArabicTextNormalizer {

    private val DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val withoutDiacritics = DIACRITICS_REGEX.replace(input, "")
        val sb = StringBuilder(withoutDiacritics.length)

        for (ch in withoutDiacritics) {
            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                else -> ch.lowercaseChar()
            }
            sb.append(mapped)
        }

        return WHITESPACE_REGEX.replace(sb.toString().trim(), " ")
    }

    /**
     * تطبيع **أسماء للمقارنة حصراً** (T7 — Issue #28، spec 04 §أ: «إزالة البادئات الشائعة عند
     * المقارنة»): يطبّق [normalize] ثم يزيح البادئات اللقبية المتكررة المطلوبة حرفياً في
     * المواصفة — «د.، م.، أ.، Dr» — من بداية الاسم.
     *
     * لماذا على الشكل الخام قبل التطبيع؟ لأن «أ.» يتحوّل تطبيعاً إلى «ا.» فيصبح تمييزه عن اسم
     * يبدأ بألف («احمد») مستحيلاً بعد التطبيع — الإزاحة على الخام تميّز النقطة والكلمة اللقبية
     * بلا غموض. الاستبعاد متكرر («د. د. أحمد» يُزاح مرتين) ويقف عند أول رمز غير لقبٍ.
     *
     * لا يُخزَّن خرجه إطلاقاً (ليس بديلاً عن [normalize] في التخزين أو البحث) — استعماله الوحيد
     * مقارنة الأسماء في كشف المكررات وJaro-Winkler.
     */
    fun normalizeForComparison(input: String): String {
        var current = input.trim()
        if (current.isEmpty()) return ""
        var previous: String
        do {
            previous = current
            for (title in COMPARISON_TITLES) {
                if (current.length > title.length &&
                    current.regionMatches(0, title, 0, title.length, ignoreCase = true)
                ) {
                    current = current.substring(title.length).trim()
                    break
                }
            }
        } while (current != previous)
        return normalize(current)
    }

    /** اللقب المقارَن بمواصفة 04 §أ حصراً (د.، م.، أ.، Dr) — مع/بدون النقطة، وDr بكلا حالتيها. */
    private val COMPARISON_TITLES = listOf("د.", "د ", "م.", "م ", "أ.", "أ ", "dr.", "dr ")
}
