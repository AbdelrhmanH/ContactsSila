package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Phone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * عقود كشف المكررات لـ T7 (Issue #28 — spec 04 §أ): اتحاد الأدلة عبر union-find، الثقeties
 * الثلاث، استبعاد الأزواج المرفوضة، والترتيب الحتمي.
 */
class DuplicateDetectionTest {

    private val detect = DetectDuplicatesUseCase()

    private fun contact(
        id: Long,
        systemId: String,
        name: String,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
    ) = Contact(
        id = id,
        systemContactId = systemId,
        name = ContactName(displayName = name),
        phones = phones.map { Phone(raw = it, e164 = it) },
        emails = emails.map { Email(address = it) },
    )

    // ——— الأدلة والثقة ———

    @Test
    fun sameE164_isHigh_transitivelyMergesThreeContacts() {
        // A–B وB–C بالهاتف: union-find يوحّدهم في مجموعة ثلاثية واحدة (كان الفحص القديم ينتج اثنتين)
        val groups = detect(
            listOf(
                contact(1, "sys_a", "أحمد الأول", phones = listOf("+201000000001")),
                contact(2, "sys_b", "أحمد الثاني", phones = listOf("+201000000001")),
                contact(3, "sys_c", "أحمد الثالث", phones = listOf("+201000000001")),
            ),
        )
        assertEquals(1, groups.size)
        val group = groups.first()
        assertEquals(DuplicateConfidence.HIGH, group.confidence)
        assertEquals(DuplicateReasonKind.SAME_PHONE, group.reasonKind)
        assertEquals(3, group.contacts.size)
        assertEquals("+201000000001", group.reasonDetail)
    }

    @Test
    fun sameEmailCaseInsensitive_isHigh() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", "سارة", emails = listOf("Sara@Example.com")),
                contact(2, "sys_b", "سارة ثانية", emails = listOf("sara@example.com")),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateConfidence.HIGH, groups.first().confidence)
        assertEquals(DuplicateReasonKind.SAME_EMAIL, groups.first().reasonKind)
        assertEquals("sara@example.com", groups.first().reasonDetail)
    }

    @Test
    fun sameNormalizedName_isMedium_andAlefVariantsFold() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", "أحمد إبراهيم"),
                contact(2, "sys_b", "احمد ابراهيم"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateConfidence.MEDIUM, groups.first().confidence)
        assertEquals(DuplicateReasonKind.SAME_NAME, groups.first().reasonKind)
    }

    @Test
    fun similarSingleWordName_aboveThreshold_isLowSuggestionOnly() {
        // زوج أحاديتا الكلمة متشابهان (JW ≥ 0.92، فرق طول ≤ 2) بلا أي دليل أدق — LOW اقتراح فقط
        val groups = detect(
            listOf(
                contact(1, "sys_a", "محمود"),
                contact(2, "sys_b", "محمودد"),
            ),
        )
        assertTrue(groups.isNotEmpty(), "التشابه فوق العتبة يُنشئ اقتراحاً")
        assertEquals(DuplicateConfidence.LOW, groups.first().confidence)
        assertEquals(DuplicateReasonKind.SIMILAR_NAME, groups.first().reasonKind)
    }

    @Test
    fun similarNames_belowThreshold_neverGroup() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", "محمد سعيد"),
                contact(2, "sys_b", "محمد كريم"),
            ),
        )
        assertTrue(groups.isEmpty(), "تحت العتبة: لا اقتراح أصلاً")
    }

    @Test
    fun patronymicSamePrefix_differentFather_neverGroup_agyF7() {
        // JW ≈ 0.943 لكن الرمز الأخير (اسم الوالد) مختلف — قاعدة الرمز-الأخير تحجب الدليل الزائف
        val groups = detect(
            listOf(
                contact(1, "sys_a", "عبدالرحمن محمد"),
                contact(2, "sys_b", "عبدالرحمن أحمد"),
            ),
        )
        assertTrue(groups.isEmpty(), "الباترونيمي لا يُجمَّع رغم JW فوق العتبة")
    }

    @Test
    fun bareShortCode_shared_isMediumNotHigh_agyF7() {
        // رمز قصير مشترك (بلا +): ثقة MEDIUM حصراً كي لا يطوي «دمج كل المؤكدة» خدمات متمايزة
        val groups = detect(
            listOf(
                contact(1, "sys_a", "بنك الأول", phones = listOf("122")),
                contact(2, "sys_b", "بنك الثاني", phones = listOf("122")),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(DuplicateConfidence.MEDIUM, groups.first().confidence)
    }

    // ——— الاستثناءات ———

    @Test
    fun blankNames_neverGroup_evenWithBlankEverywhere() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", ""),
                contact(2, "sys_b", "   "),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun partialPhoneNumber_neverMerges() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", "شخص أ", phones = listOf("+201000000001")),
                contact(2, "sys_b", "شخص ب", phones = listOf("+20100000000111")),
            ),
        )
        assertTrue(groups.isEmpty(), "لا مطابقة جزئية للأرقام إطلاقاً")
    }

    // ——— استبعاد الأزواج المرفوضة ———

    @Test
    fun dismissedPair_doesNotReappear_andNewEvidenceStillMergesLegitimately() {
        val a = contact(1, "sys_a", "أحمد إبراهيم", phones = listOf("+201000000001"))
        val b = contact(2, "sys_b", "احمد ابراهيم", phones = listOf("+201000000001"))
        val pairKey = stableSuggestionId(SuggestionCard.Kind.DUPLICATES, listOf("sys_a", "sys_b"))

        val dismissed = detect(listOf(a, b), dismissedPairKeys = setOf(pairKey))
        assertTrue(dismissed.isEmpty(), "الثنائية المرفوضة لا تعود (لا حافة أصلاً)")

        // دليل جديد عبر جهة ثالثة يوحّد الجميع — الدليل الجديد ليس الزوج المرفوض نفسه
        val c = contact(3, "sys_c", "شخص ثالث", phones = listOf("+201000000001"))
        val stillMerged = detect(listOf(a, b, c), dismissedPairKeys = setOf(pairKey))
        assertEquals(1, stillMerged.size, "اتحاد الأدلة الآخر مشروع رغم رفض الزوج A-B")
        assertEquals(3, stillMerged.first().contacts.size)
    }

    // ——— الترتيب الحتمي والهوية ———

    @Test
    fun ordering_highFirst_thenSize_thenStableId() {
        val highPair = listOf(
            contact(1, "h1", "غريب 1", phones = listOf("+201000000009")),
            contact(2, "h2", "غريب 2", phones = listOf("+201000000009")),
        )
        val mediumTrio = listOf(
            contact(3, "m1", "فاطمة عادل"),
            contact(4, "m2", "فاطمه عادل"),
            contact(5, "m3", "فاطمه عادلم"),
        )
        val groups = detect(highPair + mediumTrio)
        assertTrue(groups.size >= 2)
        assertEquals(DuplicateConfidence.HIGH, groups[0].confidence, "HIGH أولاً")
        assertEquals(DuplicateConfidence.MEDIUM, groups[1].confidence)
        assertTrue(groups[0].contacts.size == 2 && groups[1].contacts.size >= 2)

        // الثبات: نفس الإدخال بترتيب مختلف ⇒ نفس المجموعات بمعرفاتها
        val reshuffled = detect(mediumTrio.shuffled() + highPair)
        assertEquals(groups.map { it.id }, reshuffled.map { it.id }, "المعرفات ثابتة عبر ترتيب الإدخال")
    }

    @Test
    fun groupId_derivedFromMembership_andMatchesCardIdentity() {
        val groups = detect(
            listOf(
                contact(1, "sys_a", "هالة", phones = listOf("+201000000007")),
                contact(2, "sys_b", "هاله", phones = listOf("+201000000007")),
            ),
        )
        val group = groups.first()
        assertEquals(
            "duplicates:${listOf("sys_a", "sys_b").sorted().joinToString("|").hashCode()}",
            group.id,
            "المعرف بنفس اشتقاق stableSuggestionId فلا تنكسر الإغلاقات القائمة",
        )
        assertNotNull(group.id)
    }
}
