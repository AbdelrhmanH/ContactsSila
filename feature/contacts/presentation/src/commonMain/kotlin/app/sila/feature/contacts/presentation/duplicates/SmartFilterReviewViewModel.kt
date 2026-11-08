package app.sila.feature.contacts.presentation.duplicates

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppResult
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.Category
import app.sila.feature.contacts.domain.ApplySmartFilterSuggestionsUseCase
import app.sila.feature.contacts.domain.ObserveSmartFilterSuggestionsUseCase
import app.sila.feature.contacts.domain.RecordSmartFilterFeedbackUseCase
import app.sila.feature.contacts.domain.SetExcludeFromSmartFilterUseCase
import app.sila.feature.contacts.domain.SmartFilterCategoryGroup
import app.sila.feature.contacts.domain.SmartFilterConfidence
import app.sila.feature.contacts.domain.SmartFilterItem
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * حالة شاشة مراجعة الفلترة الذكية (T7 · Issue #28 — spec 04 §ب) — ViewModel مستقل
 * بعمد: [app.sila.feature.contacts.presentation.ContactsUiState] عقد مشترك مجمد مع
 * T4/T6 فلا يُمس.
 *
 * - [groups] مجموعات مقترحات القواعد (بوابة smartFilterEnabled داخل حالة الاستخدام).
 * - [checkedIds] تحديد المستخدم — يبدأ بـ **HIGH حصراً** (جدول الثقة في المواصفة:
 *   العالي يُحدد مسبقاً؛ المتوسط/الضعيف اختيارية) ويُقص على البنود الحية عند كل انبعاث.
 * - [rejectedIds] «ليست هذه الفئة» — يزيل البند من العرض ويسجل رفض التغذية الراجعة
 *   (وزن التوكن ينخفض في التقييمات القادمة — عقد docs/features/04 «يقلل وزنه»).
 */
@androidx.compose.runtime.Immutable
data class SmartFilterReviewUiState(
    val isLoading: Boolean = true,
    val groups: List<SmartFilterCategoryGroup> = emptyList(),
    val checkedIds: Set<Long> = emptySet(),
    val rejectedIds: Set<Long> = emptySet(),
    val isApplying: Boolean = false,
    val hasError: Boolean = false,
    /** عرض حوار «إضافة لأرحام» بعد تطبيق عائلة ذات مرشّح قرابة (AC — agy P1#6). */
    val pendingArhamOffer: List<SmartFilterItem> = emptyList(),
) {
    /** كل بنود العرض بعد استبعاد المرفوضة — أساس الاحتساب الموحد. */
    val visibleItems: List<SmartFilterItem>
        get() = groups.flatMap { group -> group.items.filter { it.contactId !in rejectedIds } }

    val checkedItems: List<SmartFilterItem>
        get() = visibleItems.filter { it.contactId in checkedIds }

    val canApply: Boolean
        get() = !isApplying && checkedItems.isNotEmpty()
}

sealed interface SmartFilterReviewIntent {
    data object Back : SmartFilterReviewIntent
    data class ToggleItem(val contactId: Long) : SmartFilterReviewIntent
    data class ToggleGroupAll(val category: Category) : SmartFilterReviewIntent
    data object Apply : SmartFilterReviewIntent
    data class NotThisCategory(val contactId: Long) : SmartFilterReviewIntent
    data class NeverAsk(val contactId: Long) : SmartFilterReviewIntent
    /** تأكيد عرض أرحام — يربط كل العروض المعروضة بوتيرتها الافتراضية (agy F5). */
    data object ConfirmArhamOffer : SmartFilterReviewIntent
    /** رفض عرض أرحام — رجوع بلا ربط. */
    data object DeclineArhamOffer : SmartFilterReviewIntent
}

sealed interface SmartFilterReviewEffect {
    data object NavigateBack : SmartFilterReviewEffect
    data class ShowError(val messageKey: SmartFilterReviewErrorKey) : SmartFilterReviewEffect
}

/** مفاتيح أخطاء المراجعة — النصوص في موارد الترجمة (لا نثر في النطاق ولا في الـ VM). */
enum class SmartFilterReviewErrorKey { APPLY_FAILED, CONTACT_NOT_FOUND, ARHAM_LINK_FAILED }

class SmartFilterReviewViewModel(
    observeSuggestions: ObserveSmartFilterSuggestionsUseCase,
    private val applySuggestions: ApplySmartFilterSuggestionsUseCase,
    private val recordFeedback: RecordSmartFilterFeedbackUseCase,
    private val setExclude: SetExcludeFromSmartFilterUseCase,
    private val kinshipLinker: app.sila.feature.contacts.domain.ArhamKinshipLinker,
) : BaseMviViewModel<SmartFilterReviewUiState, SmartFilterReviewIntent, SmartFilterReviewEffect>(
    SmartFilterReviewUiState(),
) {

    init {
        observeSuggestions()
            .onEach { groups ->
                updateState {
                    if (isLoading) {
                        // أول ظهور: HIGH يُحدد مسبقاً حصراً (عقد جدول الثقة)
                        copy(
                            isLoading = false,
                            hasError = false,
                            groups = groups,
                            checkedIds = groups.flatMap { group -> group.items }
                                .filter { it.confidence == SmartFilterConfidence.HIGH }
                                .map { it.contactId }
                                .toSet(),
                        )
                    } else {
                        // الانبعاثات اللاحقة تحفظ تحديد المستخدم وتقصّه على البنود الحية
                        val liveIds = groups.flatMap { group -> group.items }.map { it.contactId }.toSet()
                        copy(groups = groups, checkedIds = checkedIds intersect liveIds)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: SmartFilterReviewIntent) {
        when (intent) {
            SmartFilterReviewIntent.Back -> sendEffect(SmartFilterReviewEffect.NavigateBack)
            is SmartFilterReviewIntent.ToggleItem -> toggleItem(intent.contactId)
            is SmartFilterReviewIntent.ToggleGroupAll -> toggleGroupAll(intent.category)
            SmartFilterReviewIntent.Apply -> apply()
            is SmartFilterReviewIntent.NotThisCategory -> notThisCategory(intent.contactId)
            is SmartFilterReviewIntent.NeverAsk -> neverAsk(intent.contactId)
            SmartFilterReviewIntent.ConfirmArhamOffer -> confirmArhamOffer()
            SmartFilterReviewIntent.DeclineArhamOffer -> declineArhamOffer()
        }
    }

    private fun toggleItem(contactId: Long) {
        updateState {
            copy(
                checkedIds = if (contactId in checkedIds) checkedIds - contactId else checkedIds + contactId,
            )
        }
    }

    private fun toggleGroupAll(category: Category) {
        updateState {
            val groupIds = groups.firstOrNull { it.category == category }
                ?.items?.filter { it.contactId !in rejectedIds }?.map { it.contactId }
                ?: return@updateState this
            val allChecked = groupIds.all { it in checkedIds }
            copy(
                checkedIds = if (allChecked) checkedIds - groupIds.toSet() else checkedIds + groupIds.toSet(),
            )
        }
    }

    private fun apply() {
        val items = currentState.checkedItems
        if (items.isEmpty() || currentState.isApplying) return
        updateState { copy(isApplying = true) }
        viewModelScope.launch {
            when (val result = applySuggestions(items)) {
                is AppResult.Success -> {
                    // التطبيق نجاح — التصنيفات المكتوبة تُسقط البنود من التيار ذاتياً.
                    // AC: قبول FAMILY ذات مرشّح قرابة ← عرض «إضافة لأرحام» قبل الرجوع
                    // (agy P1#6 — الآلية كانت موجودة بلا مستدعٍ إنتاجي).
                    val arhamOffers = items.filter { item ->
                        item.category == Category.FAMILY && item.relationCandidate != null
                    }
                    updateState {
                        copy(
                            isApplying = false,
                            pendingArhamOffer = arhamOffers,
                        )
                    }
                    if (arhamOffers.isEmpty()) {
                        sendEffect(SmartFilterReviewEffect.NavigateBack)
                    }
                }
                is AppResult.Error -> {
                    updateState { copy(isApplying = false, hasError = true) }
                    sendEffect(SmartFilterReviewEffect.ShowError(SmartFilterReviewErrorKey.APPLY_FAILED))
                }
            }
        }
    }

    private fun notThisCategory(contactId: Long) {
        val item = currentState.visibleItems.firstOrNull { it.contactId == contactId } ?: return
        viewModelScope.launch {
            recordFeedback(item, accepted = false)
            updateState { copy(rejectedIds = rejectedIds + contactId) }
        }
    }

    private fun neverAsk(contactId: Long) {
        val item = currentState.visibleItems.firstOrNull { it.contactId == contactId } ?: return
        viewModelScope.launch {
            recordFeedback(item, accepted = false)
            when (setExclude(contactId)) {
                // الجهة تخرج من التيار ذاتياً بعد كتابة الاستثناء (upsert يغيّر المرصد)
                true -> Unit
                false -> sendEffect(SmartFilterReviewEffect.ShowError(SmartFilterReviewErrorKey.CONTACT_NOT_FOUND))
            }
        }
    }

    /** تأكيد العرض: ربط كل جهة عائلة معروضة بقرابتها المقترحة ووتيرتها الافتراضية (agy F5). */
    private fun confirmArhamOffer() {
        val offers = currentState.pendingArhamOffer
        if (offers.isEmpty()) return
        viewModelScope.launch {
            var failure = false
            for (offer in offers) {
                val relation = offer.relationCandidate ?: continue
                when (kinshipLinker.link(offer.contactId, relation)) {
                    is AppResult.Success -> Unit
                    is AppResult.Error -> failure = true
                }
            }
            updateState { copy(pendingArhamOffer = emptyList()) }
            if (failure) {
                sendEffect(SmartFilterReviewEffect.ShowError(SmartFilterReviewErrorKey.ARHAM_LINK_FAILED))
            }
            sendEffect(SmartFilterReviewEffect.NavigateBack)
        }
    }

    /** رفض العرض: لا ربط — رجوع. */
    private fun declineArhamOffer() {
        updateState { copy(pendingArhamOffer = emptyList()) }
        sendEffect(SmartFilterReviewEffect.NavigateBack)
    }
    /** تحرير صريح من مضيف iOS عند pop (لا ViewModelStoreOwner هناك). */
    fun clear() {
    }
}
