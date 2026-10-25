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
}
