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
 * - [undoSnapshotIds]/[isUndoBarVisible] جلسة التراجع: لقطات الجلسة كلها — التراجع يعيدها بالعكس
 *   (آخر دمج) — «التراجع خلال جلسة الشاشة»؛ مغادرة الشاشة تنهي الجلسة (اللقطة تنقضي
 *   زمنياً ويُتبعها المسح المؤجل — عقد ADR-0013).
 */
@androidx.compose.runtime.Immutable
data class DuplicatesReviewUiState(
    val isLoading: Boolean = true,
    val groups: List<DuplicateGroup> = emptyList(),
    val expandedGroupId: String? = null,
    val previews: Map<String, MergePlanner.MergePlan> = emptyMap(),
    val isMerging: Boolean = false,
    val lastMergedSummary: MergedSummary? = null,
    /** كل لقطات الجلسة بترتيب الدمج — «التراجع» يعيدها كلها بالعكس (عقد «يعيد الوضع كما كان»). */
    val undoSnapshotIds: List<Long> = emptyList(),
    val isUndoBarVisible: Boolean = false,
    val hasError: Boolean = false,
) {
    /** «دمج كل المؤكدة» = HIGH حصراً (المواصفة) — LOW/MEDIUM لا يُدمجان جماعياً أبداً. */
    val confirmedHighGroups: List<DuplicateGroup>
        get() = groups.filter { it.confidence == DuplicateConfidence.HIGH }

    data class MergedSummary(val mergedCount: Int, val survivorName: String)
}

sealed interface DuplicatesReviewIntent {
    data object Back : DuplicatesReviewIntent
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
enum class DuplicateReviewErrorKey { MERGE_FAILED, UNDO_FAILED }

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
            DuplicatesReviewIntent.Back -> sendEffect(DuplicatesReviewEffect.NavigateBack)
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
                        updateState { copy(undoSnapshotIds = undoSnapshotIds + result.data.snapshotId) }
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
                    // mergedCount == 0 (كل المجموعات تلاشت قبل التنفيذ — المزامنة أصلحتها):
                    // لا شيء التزم فلا نجاح زائف ولا إنذار — الشاشة الحية تعرض الحالة الجديدة
                    // أصلاً (أثرGROUP_CHANGED كان سيكون غير قابل للاختبار حتماً عبر أنبوب
                    // combine متعدد القفزات — التخطي الصامت عقد التزامن هنا).
                    if (mergedCount > 0) {
                        val summary = DuplicatesReviewUiState.MergedSummary(mergedCount, lastSurvivorName)
                        updateState { copy(lastMergedSummary = summary, isUndoBarVisible = true) }
                        sendEffect(DuplicatesReviewEffect.MergeCompleted(summary))
                    }
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
        val snapshots = currentState.undoSnapshotIds
        if (snapshots.isEmpty()) return
        viewModelScope.launch {
            // «يعيد الوضع كما كان»: كل لقطات الجلسة تُسترجع **بترتيب عكسي** — كل نجاح يُسقط
            // لقطته من الجلسة، وفشلُ واحدة يُبقي البقية قابلة لإعادة المحاولة (القفل
            // لمرة-واحدة يحمي المُسترجَعة سلفاً — spec P1: تراجع «دمج كل المؤكدة» يعيد الكل).
            var failure = false
            for (snapshotId in snapshots.reversed()) {
                when (undoMerge(snapshotId)) {
                    is AppResult.Success -> updateState { copy(undoSnapshotIds = undoSnapshotIds - snapshotId) }
                    is AppResult.Error -> {
                        failure = true
                        break
                    }
                }
            }
            if (failure) {
                updateState { copy(isUndoBarVisible = undoSnapshotIds.isNotEmpty()) }
                sendEffect(DuplicatesReviewEffect.UndoFailed)
            } else {
                updateState { copy(undoSnapshotIds = emptyList(), isUndoBarVisible = false, lastMergedSummary = null) }
                sendEffect(DuplicatesReviewEffect.UndoCompleted)
            }
        }
    }

    /** تحرير صريح من مضيف iOS عند pop (لا ViewModelStoreOwner هناك). */
    fun clear() {
    }
}
