package app.sila.feature.contacts.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * بطاقات الاقتراح القابلة للإغلاق (Issue #24 — نقطة دخول حصراً؛ شاشات الدمج/المراجعة
 * T7 تملك تياراتها الكاملة):
 * - **مكررات محتملة**: من [DetectDuplicatesUseCase] — بطاقة لكل مجموعة (سقف [maxDuplicateCards]).
 * - **مراجعة الفلترة الذكية** (T7 · agy F6): **تحت بوابة `smartFilterEnabled`** وعدّدها
 *   = جهات ذات **مطابقة قاعدة فعلية** حصراً (UNCATEGORIZED غير المستثناة) — لا عدّ
 *   خام لكل UNCATEGORIZED الذي كان يفتح شاشة مراجعة فارغة.
 *
 * الاستبعاد دائم لكل بطاقة عبر [ContactsListPreferencesRepository.dismissedSuggestionIds]،
 * ومعرّف البطاقة مشتق من هوية عناصرها فلا يعود إلا إذا تغيّرت المجموعة.
 */
class ObserveSuggestionsUseCase(
    private val repository: ContactsRepository,
    private val listPreferences: ContactsListPreferencesRepository,
    private val mergeStore: ContactsMergeStore,
    private val dispatchers: AppDispatchers,
    private val detectDuplicates: DetectDuplicatesUseCase = DetectDuplicatesUseCase(),
    private val engine: SmartFilterRuleEngine = SmartFilterRuleEngine(),
    private val maxDuplicateCards: Int = DEFAULT_MAX_DUPLICATE_CARDS,
) {

    operator fun invoke(): Flow<List<SuggestionCard>> = combine(
        repository.observeContacts(),
        listPreferences.dismissedSuggestionIds,
        listPreferences.smartFilterEnabled,
        mergeStore.observeFeedback(),
    ) { contacts, dismissedIds, smartFilterEnabled, feedback ->
        buildSuggestions(contacts, dismissedIds, smartFilterEnabled, feedback)
    }.flowOn(dispatchers.default) // كشف 5k + تقييم القواعد خارج Main (عرف الأداء — عقد first-frame)

    private fun buildSuggestions(
        contacts: List<Contact>,
        dismissedIds: Set<String>,
        smartFilterEnabled: Boolean,
        feedback: List<SmartFilterWeight>,
    ): List<SuggestionCard> {
        val cards = mutableListOf<SuggestionCard>()

        detectDuplicates(contacts)
            .take(maxDuplicateCards)
            .forEach { group ->
                val identityKeys = group.contacts.map { it.systemContactId }
                cards += SuggestionCard(
                    id = stableSuggestionId(SuggestionCard.Kind.DUPLICATES, identityKeys),
                    kind = SuggestionCard.Kind.DUPLICATES,
                    contactIds = group.contacts.map { it.id },
                    count = group.contacts.size
                )
            }

        // عدّ القواعد (agy F6): البطاقة تعرض عدد المطابقات الفعلية وهويّتها مشتقة من
        // المطابِقات أنفسهنّ — تغيّر مجموعة المطابقة يولّد بطاقة جديدة (عقد المعرّف)
        if (smartFilterEnabled) {
            val feedbackByKey = feedback.associateBy { it.token to it.category }
            val matchedContacts = contacts
                .filter { it.category == Category.UNCATEGORIZED && !it.excludeFromSmartFilter }
                .filter { contact -> engine.evaluate(contact, feedbackByKey) != null }
            if (matchedContacts.isNotEmpty()) {
                cards += SuggestionCard(
                    id = stableSuggestionId(
                        SuggestionCard.Kind.SMART_FILTER,
                        matchedContacts.map { it.systemContactId },
                    ),
                    kind = SuggestionCard.Kind.SMART_FILTER,
                    contactIds = matchedContacts.map { it.id },
                    count = matchedContacts.size,
                )
            }
        }
        return cards.filter { card -> card.id !in dismissedIds }
    }

    private companion object {
        const val DEFAULT_MAX_DUPLICATE_CARDS = 3
    }
}
