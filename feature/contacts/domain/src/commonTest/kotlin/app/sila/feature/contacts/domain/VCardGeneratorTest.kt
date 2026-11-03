package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.CustomField
import app.sila.core.model.Email
import app.sila.core.model.ImportantDate
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات مولّد vCard 3.0 (T6 · J-7 · Issue #27) — دالة نقية تُختبر على JVM حرفياً:
 * صيغة RFC 2426 (فاصل CRLF)، تهريب القيم، أنواع TEL/EMAIL، و**نطاق التصدير** —
 * حقول المرآة المنصوية فقط: المحتوى صِلَة-الخاص لا يُسرَب في مادة مشاركة خارجية.
 */
class VCardGeneratorTest {

    private fun contact(
        displayName: String = "منى أحمد",
        phones: List<Phone> = listOf(Phone(raw = "010 1234 5678", e164 = "+201012345678")),
    ) = Contact(
        id = 7L,
        systemContactId = "sys-7",
        lookupKey = "lookup-7",
        name = ContactName(displayName = displayName, firstName = displayName),
        phones = phones,
        normalizedName = displayName,
    )

    // ===== الهيكل الأساسي =====

    @Test
    fun minimalContact_producesRfc2426Envelope_withCrlfLineSeparators() {
        val vcard = VCardGenerator.generate(contact())

        val lines = vcard.split("\r\n")
        assertEquals("BEGIN:VCARD", lines.first())
        assertEquals("VERSION:3.0", lines[1])
        assertEquals("N:;منى أحمد;;;", lines[2], "N: family;given;middle;prefix;suffix")
        assertEquals("FN:منى أحمد", lines[3])
        assertEquals("TEL;TYPE=CELL,VOICE:010 1234 5678", lines[4])
        assertEquals("END:VCARD", lines[lines.lastIndex - 1])
        assertTrue(lines.last().isEmpty(), "آخر فاصل CRLF مكتمل")
        assertTrue(
            vcard.replace("\r\n", "").none { it == '\n' },
            "فاصل الأسطر CRLF حصراً — لا LF خام",
        )
    }

    @Test
    fun nameComponents_composeN_inFamilyGivenOrder() {
        val vcard = VCardGenerator.generate(
            contact().copy(
                name = ContactName(
                    displayName = "أ. منى أحمد علي الهاشمي",
                    firstName = "منى",
                    middleName = "أحمد",
                    lastName = "علي الهاشمي",
                    prefix = "أ.",
                    suffix = "الهاشمي",
                ),
            ),
        )

        assertTrue(vcard.contains("N:علي الهاشمي;منى;أحمد;أ.;الهاشمي\r\n"))
    }

    // ===== التهريب (RFC 2426 §4) =====

    @Test
    fun escaping_backslashSemicolonComma_andNewlines() {
        val vcard = VCardGenerator.generate(
            contact(displayName = "شركة; البيع\\، الشرقي\nالقاهرة"),
        )

        assertTrue(vcard.contains("FN:شركة\\; البيع\\\\، الشرقي\\nالقاهرة\r\n"))
    }

    @Test
    fun nameComponent_semicolon_doesNotBreakNStructure() {
        val vcard = VCardGenerator.generate(
            contact().copy(
                name = ContactName(displayName = "علي;الشرقي", firstName = "علي;الشرقي"),
            ),
        )

        // الفاصلة المنقوطة مهربة داخل المركّب — لا تُفسَّر كفاصل بين مركّبات N الخمسة.
        assertTrue(vcard.contains("N:;علي\\;الشرقي;;;\r\n"))
        assertEquals(1, vcard.split("\r\nN:").size - 1, "سطر N واحد حصراً")
    }

    // ===== أنواع TEL =====

    @Test
    fun telType_mapping_coversAllModelTypes() {
        val vcard = VCardGenerator.generate(
            contact(
                phones = listOf(
                    Phone(raw = "010", e164 = "+2010", type = "MOBILE"),
                    Phone(raw = "02", e164 = "+202", type = "WORK"),
                    Phone(raw = "02", e164 = "+202", type = "HOME"),
                    Phone(raw = "19011", e164 = "+19", type = "MAIN"),
                    Phone(raw = "02", e164 = "+202", type = "FAX"),
                    Phone(raw = "01", e164 = "+201", type = "OTHER"),
                ),
            ),
        )

        val telLines = vcard.split("\r\n").filter { it.startsWith("TEL;") }
        assertEquals(
            listOf(
                "TEL;TYPE=CELL,VOICE:010", // MOBILE
                "TEL;TYPE=WORK,VOICE:02",
                "TEL;TYPE=HOME,VOICE:02",
                "TEL;TYPE=MAIN:19011",
                "TEL;TYPE=WORK,FAX:02",
                "TEL;TYPE=CELL,VOICE:01", // غير معروف → المحمول الافتراضي
            ),
            telLines,
            "كل نوع نموذج يقابل نوع vCard صحيحاً وبترتيب الإدخال",
        )
    }

    // ===== ORG/EMAIL/URL =====

    @Test
    fun organization_companyAndDepartment_commaSeparatedEscaped() {
        val vcard = VCardGenerator.generate(
            contact().copy(organization = Organization(company = "شركة; الأفق", department = "البطاقة; الأولى")),
        )

        assertTrue(vcard.contains("ORG:شركة\\; الأفق;البطاقة\\; الأولى\r\n"))
    }

    @Test
    fun emails_internetAlways_workSuffixOnlyForWork() {
        val vcard = VCardGenerator.generate(
            contact().copy(
                emails = listOf(
                    Email(address = "mona@example.com", type = "PERSONAL"),
                    Email(address = "m.work@example.com", type = "WORK"),
                ),
            ),
        )

        assertEquals(
            listOf("EMAIL;TYPE=INTERNET:mona@example.com", "EMAIL;TYPE=INTERNET,WORK:m.work@example.com"),
            vcard.split("\r\n").filter { it.startsWith("EMAIL;") },
        )
    }

    @Test
    fun websites_eachBecomesUrlLine() {
        val vcard = VCardGenerator.generate(
            contact().copy(websites = listOf("https://example.com", "https://sila.app/about")),
        )

        assertEquals(
            listOf("URL:https://example.com", "URL:https://sila.app/about"),
            vcard.split("\r\n").filter { it.startsWith("URL:") },
        )
    }

    // ===== نطاق التصدير: المحتوى صِلَة-الخاص لا يُصدَّر إطلاقاً =====

    @Test
    fun silaOnlyFields_neverExported_evenWhenPopulated() {
        val vcard = VCardGenerator.generate(
            contact().copy(
                notes = "سرّ شخصي لا يغادر التطبيق",
                addresses = listOf(app.sila.core.model.Address(street = "شارع 9", city = "القاهرة")),
                dates = listOf(ImportantDate(type = "BIRTHDAY", month = 3, day = 21, year = 1994)),
                customFields = listOf(CustomField(label = "المهنة", value = "مهندسة")),
                category = Category.FAMILY,
                isFavorite = true,
                ringtoneUri = "content://media/ringtone/1",
                sendToVoicemail = true,
                isBlocked = true,
                arham = app.sila.core.model.ArhamInfo(
                    relation = app.sila.core.model.Relation.SISTER,
                    frequency = app.sila.core.model.ArhamFrequency.WEEKLY,
                ),
            ),
        )

        // لا خصائص vCard لهذه الحقول أصلاً (NOTE/ADR/BDAY/ITEM/CATEGORIES/X-*)
        assertFalse(vcard.contains("NOTE"), "الملاحظات صِلَة-فقط — لا تُصدَّر")
        assertFalse(vcard.contains("ADR"), "العناوين صِلَة-فقط — لا تُصدَّر")
        assertFalse(vcard.contains("BDAY"), "التواريخ صِلَة-فقط — لا تُصدَّر")
        assertFalse(vcard.contains("CATEGORIES"), "التصنيف صِلَة-فقط — لا يُصدَّر")
        // ولا قيمها مهربة في أي سطر (فحص القيم يصد أي تسرب بصيغة غير متوقعة):
        assertFalse(vcard.contains("سرّ شخصي"))
        assertFalse(vcard.contains("شارع 9"))
        assertFalse(vcard.contains("1994"))
        assertFalse(vcard.contains("مهندسة"))
        assertFalse(vcard.contains("WEEKLY"))
    }
}
