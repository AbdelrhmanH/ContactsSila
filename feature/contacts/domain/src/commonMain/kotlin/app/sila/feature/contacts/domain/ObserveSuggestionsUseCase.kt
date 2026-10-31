package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * بطاقات الاقتراح القابلة للإغلاق (Issue #24 — نقطة دخول حصراً؛ شاشات الدمج/المراجعة T7):
 * - **مكررات محتملة**: من [DetectDuplicatesUseCase] — بطاقة لكل مجموعة (سقف [maxDuplicateCards]).
 * - **مراجعة الفلترة الذكية**: جهات غير مصنّفة تنتظر تصنيفاً — بطاقة واحدة.
 *
 * الاستبعاد دائم لكل بطاقة عبر [ContactsListPreferencesRepository.dismissedSuggestionIds]،
 * ومعرّف البطاقة مشتق من هوية عناصرها فلا يعود إلا إذا تغيّرت المجموعة.
 */
class ObserveSuggestionsUseCase(
    private val repository: ContactsRepository,
    private val listPreferences: ContactsListPreferencesRepository,
    private val detectDuplicates: DetectDuplicatesUseCase = DetectDuplicatesUseCase(),
    private val maxDuplicateCards: Int = DEFAULT_MAX_DUPLICATE_CARDS,
) {

    operator fun invoke(): Flow<List<SuggestionCard>> = combine(
        repository.observeContacts(),
        listPreferences.dismissedSuggestionIds
    ) { contacts, dismissedIds ->
        buildSuggestions(contacts)
            .filter { card -> card.id !in dismissedIds }
    }

    private fun buildSuggestions(contacts: List<Contact>): List<SuggestionCard> {
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

        val uncategorizedIds = contacts.filter { it.category == Category.UNCATEGORIZED }
        if (uncategorizedIds.isNotEmpty()) {
            val identityKeys = uncategorizedIds.map { it.systemContactId }
            cards += SuggestionCard(
                id = stableSuggestionId(SuggestionCard.Kind.SMART_FILTER, identityKeys),
                kind = SuggestionCard.Kind.SMART_FILTER,
                contactIds = uncategorizedIds.map { it.id },
                count = uncategorizedIds.size
            )
        }
        return cards
    }

    private companion object {
        const val DEFAULT_MAX_DUPLICATE_CARDS = 3
    }
}
