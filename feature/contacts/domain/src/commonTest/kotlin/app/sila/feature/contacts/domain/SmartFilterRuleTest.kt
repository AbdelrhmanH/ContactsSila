package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * عقود محرك الفلترة الذكية (T7 · Issue #28 — spec 04 §ب): المطابقة **رمز-كامل حصراً**
 * («محمد دكتوري» لا تصبح SERVICES — فشل contains() القديم الذي رصده Lane C)، القاموس
 * المضمّن متعدد اللهجات، سلّم الثقة (جدول المواصفة)، relationCandidate لمصطلحات
 * القرابة، وprofessionId للخدمات (agy F6).
 */
class SmartFilterRuleTest {

    private fun candidate(
        name: String,
        e164: String? = null,
        hasCompany: Boolean = false,
        contactId: Long = 1L,
    ): SmartFilterCandidate {
        val engine = SmartCategorizeContactUseCase() // لأجل التوكنة الموحدة عبر المحرك فقط
        return SmartFilterCandidate(
            contactId = contactId,
            nameTokens = engineTokens(name),
            e164Phone = e164,
            hasCompany = hasCompany,
        )
    }

    /** نفس مسار التوكنة في حالة الاستخدام المُلاحِظة (normalize + split). */
    private fun engineTokens(name: String): List<String> =
        app.sila.core.common.ArabicTokenizer.split(app.sila.core.common.ArabicTextNormalizer.normalize(name))

    private val family = FamilyCategorizationRule()
    private val service = ServiceCategorizationRule()
    private val emergency = EmergencyCategorizationRule()
    private val work = WorkCategorizationRule()

    // ——— المطابقة الرمزية الحصراً (agy — إصلاح contains) ———

    @Test
    fun tokenExact_noFalseServiceForPossessiveName() {
        assertNull(service.evaluateSuggestion(candidate("محمد دكتوري")), "«دكتوري» ≠ «دكتور» — لا substring")
        assertNull(service.evaluateSuggestion(candidate("دكتوراه في الفيزياء")))
        assertNotNull(service.evaluateSuggestion(candidate("دكتورة سارة")), "«دكتوره» مصطلح قاموس صريح")
    }

    @Test
    fun serviceKeyword_matchesWholeToken() {
        val suggestion = service.evaluateSuggestion(candidate("دكتور أحمد"))
        assertNotNull(suggestion)
        assertEquals(Category.SERVICES, suggestion.category)
        assertEquals(SmartFilterConfidence.HIGH, suggestion.confidence)
        assertEquals("doctor", suggestion.professionId, "agy F6 — SERVICES تحمل professionId")
        assertEquals("دكتور", suggestion.matchedToken)
    }

    @Test
    fun familyKeyword_stripsDefiniteArticle_andMapsRelation() {
        val suggestion = family.evaluateSuggestion(candidate("العم حسن"))
        assertNotNull(suggestion, "«العم» → رمز «عم» بعد اقتطاع الأداة (رمز-كامل في الطرفين)")
        assertEquals(Category.FAMILY, suggestion.category)
        assertEquals(SmartFilterConfidence.HIGH, suggestion.confidence)
        assertEquals(Relation.UNCLE_PATERNAL, suggestion.relationCandidate)
    }

    @Test
    fun familyPhrase_twoTokenWindow_mapsCousin() {
        val paternal = family.evaluateSuggestion(candidate("محمد ابن عمي"))
        assertNotNull(paternal)
        assertEquals(Relation.COUSIN_PATERNAL, paternal.relationCandidate)

        val maternal = family.evaluateSuggestion(candidate("بنت خالتي"))
        assertNotNull(maternal)
        assertEquals(Relation.COUSIN_MATERNAL, maternal.relationCandidate)
    }

    @Test
    fun family_dialectTerms_gulfAndLevantine() {
        assertEquals(Relation.BROTHER, family.evaluateSuggestion(candidate("خوي خالد"))?.relationCandidate)
        assertEquals(Relation.MOTHER, family.evaluateSuggestion(candidate("يمه نجاة"))?.relationCandidate)
        assertEquals(Relation.GRANDMOTHER, family.evaluateSuggestion(candidate("سيتي أم سعيد"))?.relationCandidate)
        assertEquals(Relation.IN_LAW, family.evaluateSuggestion(candidate("حمايا أبو جورج"))?.relationCandidate)
    }

    @Test
    fun familyTerm_isNotMatchedAsSubstringOfOtherName() {
        assertNull(family.evaluateSuggestion(candidate("عميل بنك")), "«عمي» ليس جزءاً من «عميل»")
        assertNull(family.evaluateSuggestion(candidate("سمير اخضر")), "«اخي» ليس جزءاً من «اخضر»")
    }

    // ——— الطوارئ ———

    @Test
    fun emergencyKeyword_isHigh() {
        val suggestion = emergency.evaluateSuggestion(candidate("شرطة النجدة"))
        assertNotNull(suggestion)
        assertEquals(Category.EMERGENCY, suggestion.category)
        assertEquals(SmartFilterConfidence.HIGH, suggestion.confidence)
    }

    @Test
    fun emergencyShortCode_isHigh_andLongPersonalNumberNeverMatches() {
        val hotline = emergency.evaluateSuggestion(candidate("النجدة", e164 = "+20180"))
        assertEquals(SmartFilterConfidence.HIGH, hotline?.confidence)

        val personal = emergency.evaluateSuggestion(candidate("أحمد", e164 = "+201000000123"))
        assertNull(personal, "رقم شخصي طويل لا يطابق رمز طوارئ ولو انتهى به")
    }

    // ——— الخدمات: رمز قصير وأرضي = LOW ———

    @Test
    fun shortHotlineNumber_isServicesLow() {
        val suggestion = service.evaluateSuggestion(candidate("خدمة العملاء", e164 = "+2019000"))
        assertNull(suggestion, "الرمز القصير للطوارئ يبقى Emergency أولاً — وإلا فالخدمة LOW")

        val landline = service.evaluateSuggestion(candidate("مكتب البريد", e164 = "+20223345678"))
        assertNotNull(landline, "أرضي مصري 02 وطني 10 خانات")
        assertEquals(Category.SERVICES, landline.category)
        assertEquals(SmartFilterConfidence.LOW, landline.confidence)

        val mobile = service.evaluateSuggestion(candidate("صاحبي وليد", e164 = "+201012345678"))
        assertNull(mobile, "موبايل مصري ليس أرضياً — و«صاحبي» عائلة في القاعدة الأسبق")
    }

    // ——— العمل ———

    @Test
    fun companyField_isWorkLow() {
        val suggestion = work.evaluateSuggestion(candidate("أحمد محمود", hasCompany = true))
        assertNotNull(suggestion)
        assertEquals(Category.WORK, suggestion.category)
        assertEquals(SmartFilterConfidence.LOW, suggestion.confidence)
    }

    @Test
    fun engineerTitle_isWorkMedium() {
        val suggestion = work.evaluateSuggestion(candidate("مهندس طارق"))
        assertNotNull(suggestion)
        assertEquals(SmartFilterConfidence.MEDIUM, suggestion.confidence)
    }

    // ——— المحرك: أعلى ثقة تفوز (ترتيب القواعد لا يكسرها) ———

    @Test
    fun smartCategorize_picksHighestConfidence_acrossRules() {
        // شركة (WORK LOW) + مصطلح خدمة (SERVICES HIGH) → الأعلى يفوز
        assertEquals(Category.SERVICES, SmartCategorizeContactUseCase()("مطعم النور", "01012345678", hasCompany = true))
        // لقب مهني (WORK MEDIUM) + شركة (LOW) → MEDIUM يفوز ولا يزال WORK
        assertEquals(Category.WORK, SmartCategorizeContactUseCase()("م. طارق علي", "01012345678", hasCompany = true))
        // بلا أي دليل → UNCATEGORIZED
        assertEquals(Category.UNCATEGORIZED, SmartCategorizeContactUseCase()("أحمد سمير", "01012345678"))
    }

    // ——— القاموس نفسه ———

    @Test
    fun dictionary_terms_arePostNormalizationForm() {
        // كل مصطلح = صورته بعد التطبيع (همزات→ا، ة→ه، ى→ي) — عرف القاموس الموثق
        val normalizer = app.sila.core.common.ArabicTextNormalizer
        (SmartFilterDictionary.FAMILY_TERMS.keys + SmartFilterDictionary.SERVICE_TERMS.keys +
            SmartFilterDictionary.EMERGENCY_TERMS).forEach { term ->
            val normalizedParts = term.split(' ').map { part -> normalizer.normalize(part) }
            assertTrue(
                normalizedParts == term.split(' '),
                "مصطلح غير مطبَّع في القاموس: «$term» ← ${normalizedParts.joinToString(" ")}",
            )
        }
    }

    @Test
    fun dictionary_relationMapCoversAllFamilyTerms() {
        // كل مصطلح عائلة يحمل مرشّح قرابة (agy F5 — جسر أرحام يبني على المرشّح مباشرة)
        SmartFilterDictionary.FAMILY_TERMS.forEach { (term, relation) ->
            assertTrue(
                relation in Relation.entries,
                "علاقة غير معروفة للمصطلح «$term»: $relation",
            )
        }
    }
}
