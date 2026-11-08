package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.ArabicTokenizer
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Category
import app.sila.core.model.Contact

/**
 * محرك الفلترة الذكية (T7 · Issue #28 — agy F6): تقييم قواعد [ContactCategorizationRule]
 * على مرشّح مُجهَّز مرة واحدة (تطبيع + توكنة)، واختيار **الأعلى ثقةً** بترتيب القواعد
 * حكماً على التعادل (HIGH أدنى ترتيباً في التعداد — فخّ ترتيب التعداد موثق باختباره).
 * مستهلكاه: [ObserveSmartFilterSuggestionsUseCase] (المراجعة الكاملة) و
 * [ObserveSuggestionsUseCase] (عدّ بطاقة الدخول — agy F6: عدّ مطابقات القواعد حصراً).
 */
class SmartFilterRuleEngine(
    private val rules: List<ContactCategorizationRule> = SmartCategorizeContactUseCase.defaultRules(),
) {

    fun candidateOf(contact: Contact): SmartFilterCandidate = SmartFilterCandidate(
        contactId = contact.id,
        nameTokens = ArabicTokenizer.split(ArabicTextNormalizer.normalize(contact.name.displayName)),
        e164Phone = contact.phones.firstOrNull { it.isPrimary }?.e164?.takeIf { it.isNotBlank() }
            ?: contact.phones.firstOrNull()?.raw?.let { PhoneNormalizer.normalizeToE164(it) },
        hasCompany = !contact.organization?.company.isNullOrBlank(),
    )

    fun evaluateBest(candidate: SmartFilterCandidate): CategorizationSuggestion? =
        rules.mapNotNull { it.evaluateSuggestion(candidate) }
            .minByOrNull { it.confidence.ordinal }

    /** اقتراح جاهز للعرض بعد تثقيل التغذية الراجعة (الرفض المسيطر يُنزل درجة). */
    fun evaluate(
        contact: Contact,
        feedbackByKey: Map<Pair<String, Category>, SmartFilterWeight>,
    ): SmartFilterItem? {
        val best = evaluateBest(candidateOf(contact)) ?: return null
        val weight = feedbackByKey[best.matchedToken to best.category]
        val confidence = if (weight?.isRejectedDominant == true) best.confidence.downgrade() else best.confidence
        return SmartFilterItem(
            contactId = contact.id,
            displayName = contact.name.displayName,
            category = best.category,
            confidence = confidence,
            matchedToken = best.matchedToken,
            professionId = best.professionId,
            relationCandidate = best.relationCandidate,
        )
    }

    /** عدد جهات UNCATEGORIZED غير المستثناة ذات مطابقة قاعدة فعلية — عدّ بطاقة الدخول. */
    fun countMatches(contacts: List<Contact>): Int =
        contacts.count { contact ->
            contact.category == Category.UNCATEGORIZED &&
                !contact.excludeFromSmartFilter &&
                evaluateBest(candidateOf(contact)) != null
        }

    private fun SmartFilterConfidence.downgrade(): SmartFilterConfidence = when (this) {
        SmartFilterConfidence.HIGH -> SmartFilterConfidence.MEDIUM
        SmartFilterConfidence.MEDIUM -> SmartFilterConfidence.LOW
        SmartFilterConfidence.LOW -> SmartFilterConfidence.LOW
    }
}
