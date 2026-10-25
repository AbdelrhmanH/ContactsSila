package app.sila.core.common

/**
 * تطبيع أرقام الهواتف إلى صيغة E.164 (PRD §6.3, §7, §8).
 * يدعم الأرقام الدولية، بادئة 00، والأرقام المحلية (افتراضياً مصر +20، مع دعم الخليج).
 * يكتشف أيضاً الأرقام المختصرة والطارئة (مثل 122، 123، 19xxx).
 */
object PhoneNormalizer {

    private const val DEFAULT_COUNTRY_CODE = "20" // مصر افتراضياً

    fun normalizeToE164(rawPhone: String, defaultCountryCode: String = DEFAULT_COUNTRY_CODE): String {
        val digitsAndPlus = buildString {
            for (ch in rawPhone.trim()) {
                when {
                    ch == '+' && isEmpty() -> append('+')
                    ch in '0'..'9' -> append(ch)
                    ch in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                    ch in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                }
            }
        }

        if (digitsAndPlus.isEmpty()) return ""

        // إذا كان رقماً مختصراً أو طوارئ (3 إلى 6 أرقام بدون +) يبقى كما هو
        if (!digitsAndPlus.startsWith("+") && !digitsAndPlus.startsWith("00") && digitsAndPlus.length in 3..6) {
            return digitsAndPlus
        }

        return when {
            digitsAndPlus.startsWith("+") -> digitsAndPlus
            digitsAndPlus.startsWith("00") -> "+" + digitsAndPlus.substring(2)
            digitsAndPlus.startsWith("0") -> "+$defaultCountryCode${digitsAndPlus.substring(1)}"
            digitsAndPlus.startsWith(defaultCountryCode) && digitsAndPlus.length >= 11 -> "+$digitsAndPlus"
            else -> "+$defaultCountryCode$digitsAndPlus"
        }
    }

    /**
     * يحدد إن كان الرقم رقماً مختصراً للخدمات أو الطوارئ (مثل 122، 123، 180، 19xxx).
     */
    fun isShortCodeOrHotline(rawPhone: String): Boolean {
        val normalized = normalizeToE164(rawPhone)
        return normalized.isNotEmpty() && !normalized.startsWith("+") && normalized.length in 3..6
    }

    /**
     * يتحقق من صلاحية الرقم للاستخدام.
     */
    fun isValidPhone(rawPhone: String): Boolean {
        val normalized = normalizeToE164(rawPhone)
        if (normalized.isEmpty()) return false
        if (isShortCodeOrHotline(rawPhone)) return true
        val digitsOnly = normalized.removePrefix("+")
        return digitsOnly.length in 8..15 && digitsOnly.all { it.isDigit() }
    }
}
