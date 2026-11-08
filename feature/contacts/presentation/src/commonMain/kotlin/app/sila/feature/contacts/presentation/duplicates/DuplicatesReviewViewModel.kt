package app.sila.feature.contacts.presentation.duplicates

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.common.AppResult
import app.sila.feature.contacts.domain.DismissDuplicateGroupUseCase
import app.sila.feature.contacts.domain.DuplicateConfidence
import app.sila.feature.contacts.domain.DuplicateGroup
import app.sila.feature.contacts.domain.MergePlanner
import app.sila.feature.contacts.domain.MergeContactsUseCase
import app.sila.feature.contacts.domain.ObserveDuplicateGroupsUseCase
import app.sila.feature.contacts.domain.UndoMergeUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * حالة شاشة مراجعة المكررات (T7 · Issue #28 — spec 04 §أ «شاشة المكررات») — ViewModel
 * مستقل بعمد: [app.sila.feature.contacts.presentation.ContactsUiState] عقد مشترك مجمد
 * مع T4/T6 (تحذير سطري الملف الأصلي) فلا يُمس.
 *
 * - [groups] المجموعات الكاملة غير المقتطعة (مرتبة: HIGH أولاً ثم الحجم — عقد الكشف).
 * - [previews] معاينة الدمج لكل مجموعة (نقي عبر [MergePlanner]) — تُحسب عند التوسيع
 *   حصراً (أداء: لا معاينات لكل المجموعات عند كل انبعاث — agy/Q8).
 * - [undoSnapshotId]/[isUndoBarVisible] جلسة التراجع: لقطة دمج واحدة نشطة في المرة
 *   (آخر دمج) — «التراجع خلال جلسة الشاشة»؛ مغادرة الشاشة تنهي الجلسة (اللقطة تنقضي
 *   زمنياً ويُتبعها المسح المؤجل — عقد ADR-0013).
 */
data class DuplicatesReviewUiState(
    val isLoading: Boolean = true,
    val groups: List<DuplicateGroup> = emptyList(),
    val expandedGroupId: String? = null,
    val previews: Map<String, MergePlanner.MergePlan> = emptyMap(),
    val isMerging: Boolean = false,
    val lastMergedSummary: MergedSummary? = null,
    val undoSnapshotId: Long? = null,
    val isUndoBarVisible: Boolean = false,
    val hasError: Boolean = false,
) {
    /** «دمج كل المؤكدة» = HIGH حصراً (المواصفة) — LOW/MEDIUM لا يُدمجان جماعياً أبداً. */
    val confirmedHighGroups: List<DuplicateGroup>
        get() = groups.filter { it.confidence == DuplicateConfidence.HIGH }

    data class MergedSummary(val mergedCount: Int, val survivorName: String)
}

sealed interface DuplicatesReviewIntent {
    data class ToggleExpanded(val groupId: String) : DuplicatesReviewIntent
    data class MergeGroup(val groupId: String) : DuplicatesReviewIntent
    data object MergeAllConfirmed : DuplicatesReviewIntent
    data class MarkNotDuplicates(val groupId: String) : DuplicatesReviewIntent
    data object UndoMerge : DuplicatesReviewIntent
    data object DismissUndoBar : DuplicatesReviewIntent
}

sealed interface DuplicatesReviewEffect {
    data object NavigateBack : DuplicatesReviewEffect
    data class MergeCompleted(val summary: DuplicatesReviewUiState.MergedSummary) : DuplicatesReviewEffect
    data object UndoCompleted : DuplicatesReviewEffect
    data object UndoFailed : DuplicatesReviewEffect
    data class ShowError(val messageKey: DuplicateReviewErrorKey) : DuplicatesReviewEffect
}

/** مفاتيح أخطاء المراجعة — النصوص في موارد الترجمة (لا نثر في النطاق ولا في الـ VM). */
enum class DuplicateReviewErrorKey { MERGE_FAILED, UNDO_FAILED, GROUP_CHANGED }

class DuplicatesReviewViewModel(
    private val observeGroups: ObserveDuplicateGroupsUseCase,
    private val mergeContacts: MergeContactsUseCase,
    private val undoMerge: UndoMergeUseCase,
    private val dismissGroup: DismissDuplicateGroupUseCase,
) : BaseMviViewModel<DuplicatesReviewUiState, DuplicatesReviewIntent, DuplicatesReviewEffect>(
    DuplicatesReviewUiState(),
) {

    init {
        observeGroups()
            .onEach { groups ->
                updateState {
                    copy(
                        isLoading = false,
                        hasError = false,
                        groups = groups,
                        // المجموعات المتغيرة تلغي التوسيع والمعاينات العالقة (الهوية ثابتة بالأعضاء)
                        expandedGroupId = expandedGroupId?.takeIf { id -> groups.any { it.id == id } },
                        previews = previews.filterKeys { id -> groups.any { it.id == id } },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: DuplicatesReviewIntent) {
        when (intent) {
            is DuplicatesReviewIntent.ToggleExpanded -> toggleExpanded(intent.groupId)
            is DuplicatesReviewIntent.MergeGroup -> mergeOne(intent.groupId)
            DuplicatesReviewIntent.MergeAllConfirmed -> mergeAllConfirmed()
            is DuplicatesReviewIntent.MarkNotDuplicates -> markNotDuplicates(intent.groupId)
            DuplicatesReviewIntent.UndoMerge -> undoLastMerge()
            DuplicatesReviewIntent.DismissUndoBar -> updateState { copy(isUndoBarVisible = false) }
        }
    }

    private fun toggleExpanded(groupId: String) {
        updateState {
            val nowExpanded = expandedGroupId != groupId
            val updatedPreviews = if (nowExpanded) {
                val group = groups.firstOrNull { it.id == groupId } ?: return@updateState this
                previews + (groupId to MergePlanner.plan(group.contacts))
            } else {
                previews - groupId
            }
            copy(expandedGroupId = if (nowExpanded) groupId else null, previews = updatedPreviews)
        }
    }

    private fun mergeOne(groupId: String) {
        val group = currentState.groups.firstOrNull { it.id == groupId } ?: return
        if (currentState.isMerging) return
        executeMerge(listOf(group))
    }

    private fun mergeAllConfirmed() {
        if (currentState.isMerging) return
        executeMerge(currentState.confirmedHighGroups)
    }

    private fun executeMerge(groups: List<DuplicateGroup>) {
        if (groups.isEmpty()) return
        updateState { copy(isMerging = true) }
        viewModelScope.launch {
            var mergedCount = 0
            var lastSurvivorName = ""
            var failure: DuplicateReviewErrorKey? = null
            for (group in groups) {
                // المجموعة قد تتبدد بين العرض والتنفيذ (مزامنة) — تخطٍّ صامت لمجموعة زالت
                val current = currentState.groups.firstOrNull { it.id == group.id } ?: continue
                when (val result = mergeContacts(current.contacts)) {
                    is AppResult.Success -> {
                        mergedCount++
                        lastSurvivorName = result.data.plan.merged.name.displayName
                        updateState { copy(undoSnapshotId = result.data.snapshotId) }
                    }
                    is AppResult.Error -> {
                        failure = DuplicateReviewErrorKey.MERGE_FAILED
                        break
                    }
                }
            }
            updateState { copy(isMerging = false) }
            when (failure) {
                null -> {
                    val summary = DuplicatesReviewUiState.MergedSummary(mergedCount, lastSurvivorName)
                    updateState { copy(lastMergedSummary = summary, isUndoBarVisible = mergedCount > 0) }
                    sendEffect(DuplicatesReviewEffect.MergeCompleted(summary))
                }
                else -> {
                    updateState { copy(hasError = true) }
                    sendEffect(DuplicatesReviewEffect.ShowError(failure))
                }
            }
        }
    }

    private fun markNotDuplicates(groupId: String) {
        val group = currentState.groups.firstOrNull { it.id == groupId } ?: return
        viewModelScope.launch {
            dismissGroup(group)
            // تيار المجموعات سيُصدِر التحديث ذاتياً (الزوج المرفوض لا يُنشئ حافةً)
        }
    }

    private fun undoLastMerge() {
        val snapshotId = currentState.undoSnapshotId ?: return
        viewModelScope.launch {
            when (val result = undoMerge(snapshotId)) {
                is AppResult.Success -> {
                    updateState { copy(undoSnapshotId = null, isUndoBarVisible = false, lastMergedSummary = null) }
                    sendEffect(DuplicatesReviewEffect.UndoCompleted)
                }
                is AppResult.Error -> sendEffect(DuplicatesReviewEffect.UndoFailed)
            }
        }
    }
}
