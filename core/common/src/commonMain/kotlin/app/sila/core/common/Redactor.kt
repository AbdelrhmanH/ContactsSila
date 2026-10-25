package app.sila.core.common

/**
 * منقّي البيانات الشخصية (PII Scrubber) للسجلات وتقارير الأعطال (SECURITY_PRIVACY.md §11).
 * يمنع تسرب أي رقم هاتف أو بريد إلكتروني إلى السجلات.
 */
object Redactor {

    private val PHONE_PATTERN = Regex("(\\+?\\d[\\d\\s\\-()]{5,}\\d)")
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

    fun scrub(message: String): String {
        if (message.isBlank()) return message
        val withoutEmails = EMAIL_PATTERN.replace(message, "[REDACTED_EMAIL]")
        return PHONE_PATTERN.replace(withoutEmails, "[REDACTED_PHONE]")
    }
}
