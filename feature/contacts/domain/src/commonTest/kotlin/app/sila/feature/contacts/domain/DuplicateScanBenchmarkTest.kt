package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * معيار الأداء الرسمي (PERFORMANCE.md — Issue #28): فحص 5,000 جهة ≤ 3 ثوانٍ.
 *
 * **نطاق القياس الموثق (لا مبالغة):** قياس مضيف JVM حتمي (حساب نقي بلا I/O — التطبيع
 * والتوكنة والدلاء وJW) بقيم ثابتة التوزيع، فالنتيجة قابلة للتكرار عبر التشغيلات.
 * قياس الجهاز الفعلي (Redmi 9S) يبقى متابعة توثيقية في تقرير PR — لا يُستدل منه نجاح
 * جهازٍ من مضيف (قاعدة T8: «لا PASS مستنتج من المحاكي/JVM»).
 *
 * التوزيع حتمي: كل 20 جهة، الثانية تشارك الأولى رقمها E.164 (250 مجموعة HIGH من
 * اثنين) — والبقية مستقلة بالأسماء والأرقام فيُتوقع عدد المجموعات حرفياً (250).
 */
class DuplicateScanBenchmarkTest {

    private val detect = DetectDuplicatesUseCase()

    private fun fixture(index: Int): Contact {
        val sharesPhone = index % 20 == 1 // مع كل 20: واحدة تشارك سابقتها الرقم
        val phoneE164 = "+2010" + "%06d".format(if (sharesPhone) index - 1 else index)
        return Contact(
            id = index + 1L,
            systemContactId = "bench_$index",
            name = ContactName(displayName = "جهة الأداء رقم $index"),
            phones = listOf(Phone(raw = phoneE164, e164 = phoneE164)),
            createdAt = index * 100L,
        )
    }

    @Test
    fun fiveThousandContacts_scanUnderThreeSeconds_deterministic() {
        val contacts = (0 until 5_000).map(::fixture)

        val startedAt = System.nanoTime()
        val groups = detect(contacts)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(250, groups.size, "التوزيع الحتمي: 250 ثنائية برقم مشترك حصراً")
        assertTrue(
            elapsedMillis < 3_000,
            "معيار PERFORMANCE.md: فحص 5,000 جهة < 3,000ms — الفعلي: ${elapsedMillis}ms",
        )
    }
}
