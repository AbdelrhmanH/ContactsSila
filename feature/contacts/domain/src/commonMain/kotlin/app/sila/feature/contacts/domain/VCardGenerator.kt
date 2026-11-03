package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import app.sila.core.model.Phone

/**
 * مولّد vCard 3.0 — **نقي 100%** (Kotlin مشترك، قرار T6 · J-7 · Issue #27 AC «مشاركة
 * vCard»): بلا أي اعتماد منصوي، يُختبر على JVM حرفياً.
 *
 * **نطاق التصدير (قرار خصوصي موثق في ADR-0012):** تُصدَّر حقول **المرآة المنصوية
 * فقط** (الاسم/الأرقام/البريد/المواقع/المؤسسة) — الحقول صِلَة-فقط (الملاحظات/التواريخ/
 * العناوين/الحقول المخصصة/التصنيف) **لا تُصدَّر إطلاقاً**: ملف vCard مادة مشاركة
 * خارج التطبيق، ومحتوى صِلَة الخاص لا يُسرَب بحفظ المستخدم إعادة مشاركته.
 *
 * الصيغة: RFC 2426 (vCard 3.0) حرفياً — فاصل أسطر CRLF، تهريب نصي للقيم
 * (`\` `;` `,` والأسطر الجديدة)، وأنواع TEL/EMAIL مقابلة لأنواع النموذج.
 */
object VCardGenerator {

    fun generate(contact: Contact): String = buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        // N: family;given;middle;prefix;suffix — مركّبات مهربة فردية.
        append("N:")
        append(
            listOf(
                contact.name.lastName,
                contact.name.firstName,
                contact.name.middleName,
                contact.name.prefix,
                contact.name.suffix,
            ).joinToString(";") { escape(it) }
        )
        append("\r\n")
        append("FN:").append(escape(contact.name.displayName)).append("\r\n")
        contact.organization?.company?.let { company ->
            append("ORG:").append(
                listOf(company, contact.organization?.department)
                    .joinToString(";") { escape(it ?: "") }
            ).append("\r\n")
        }
        contact.phones.forEach { phone ->
            append("TEL;TYPE=").append(telType(phone)).append(":").append(escape(phone.raw)).append("\r\n")
        }
        contact.emails.forEach { email ->
            append("EMAIL;TYPE=INTERNET")
            if (email.type.equals("WORK", ignoreCase = true)) append(",WORK")
            append(":").append(escape(email.address)).append("\r\n")
        }
        contact.websites.forEach { url ->
            append("URL:").append(escape(url)).append("\r\n")
        }
        append("END:VCARD\r\n")
    }

    /** RFC 2426 §4: تهريب `\` ثم `;` و`,` ونهايات الأسطر داخل قيم النص. */
    private fun escape(value: String?): String = value
        ?.replace("\\", "\\\\")
        ?.replace(";", "\\;")
        ?.replace(",", "\\,")
        ?.replace("\r\n", "\\n")
        ?.replace("\n", "\\n")
        ?.replace("\r", "\\n")
        ?: ""

    private fun telType(phone: Phone): String = when (phone.type.uppercase()) {
        "WORK" -> "WORK,VOICE"
        "HOME" -> "HOME,VOICE"
        "MAIN" -> "MAIN"
        "FAX" -> "WORK,FAX"
        else -> "CELL,VOICE" // MOBILE والافتراضي
    }
}
