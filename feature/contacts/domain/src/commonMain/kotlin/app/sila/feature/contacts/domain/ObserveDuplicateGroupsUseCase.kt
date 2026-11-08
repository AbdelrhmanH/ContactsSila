package app.sila.feature.contacts.domain

import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * مجموعات المكررات الكاملة غير المقتطعة (T7 · Issue #28) — تيار شاشة المراجعة: كشف حيّ
 * فوق [DetectDuplicatesUseCase] مع استبعاد الأزواج المرفوضة (`duplicate_dismissals`)
 * حصراً — بطاقات الاقتراض المقتطعة (≤3) تظل عقد [ObserveSuggestionsUseCase] (Issue #24).
 */
class ObserveDuplicateGroupsUseCase(
    private val repository: ContactsRepository,
    private val mergeStore: ContactsMergeStore,
    private val detectDuplicates: DetectDuplicatesUseCase = DetectDuplicatesUseCase(),
) {

    operator fun invoke(): Flow<List<DuplicateGroup>> =
        combine(
            repository.observeContacts(),
            mergeStore.observeDismissedPairKeys(),
        ) { contacts, dismissedPairKeys ->
            detectDuplicates(contacts, dismissedPairKeys)
        }.distinctUntilChanged()
}

/**
 * «ليست مكررات» على مستوى المجموعة (T7 · Issue #28): يُسجّل استبعاد **كل الأزواج** داخل
 * المجموعة (كل ثنائية بمفتاحها الثابت = معرف بطاقتها الثنائية) — كشفٌ مستقبلي لا ينشئ
 * حافةً لأي زوج مرفوض، والدليل الجديد عبر جهة ثالثة يبقى مشروعاً (عقد الكشف).
 */
class DismissDuplicateGroupUseCase(
    private val mergeStore: ContactsMergeStore,
    private val clock: app.sila.core.common.AppClock,
) {

    suspend operator fun invoke(group: DuplicateGroup) {
        val now = clock.nowEpochMillis()
        val members = group.contacts
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                mergeStore.dismissPair(
                    groupKey = stableSuggestionId(
                        SuggestionCard.Kind.DUPLICATES,
                        listOf(members[i].systemContactId, members[j].systemContactId),
                    ),
                    reason = DuplicateDismissalReason.NOT_DUPLICATES,
                    nowMillis = now,
                )
            }
        }
    }

    /** أسباب الاستبعاد الموثقة — قابلة للتوسيع بلا كسر الأسماء. */
    object DuplicateDismissalReason {
        const val NOT_DUPLICATES = "NOT_DUPLICATES"
    }
}

/** محتوى زوج مرشّح للاستبعاد — يُستهلك من واجهة المراجعة عند صف عضوٍ واحد. */
fun duplicatePairKey(first: Contact, second: Contact): String =
    stableSuggestionId(SuggestionCard.Kind.DUPLICATES, listOf(first.systemContactId, second.systemContactId))
