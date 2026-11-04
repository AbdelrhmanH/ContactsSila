package app.sila.feature.arham.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppClock
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.ArhamStatus
import app.sila.feature.arham.domain.CelebrateTransitionUseCase
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ArhamUiState(
    val period: ArhamPeriod = ArhamPeriod.WEEK,
    val items: List<ArhamItem> = emptyList(),
    val stats: ArhamDashboardStats = ArhamDashboardStats()
)

sealed interface ArhamIntent {
    data class ChangePeriod(val period: ArhamPeriod) : ArhamIntent
    data class RecordCall(val contactId: Long, val phone: String) : ArhamIntent
}

sealed interface ArhamEffect {
    data class Dial(val phone: String) : ArhamEffect
    data object ShowCelebrationConfetti : ArhamEffect
}

/**
 * ViewModel ميزة أرحام — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 *
 * T8-06 (خطة §D.3-4/§D.3-5 · Issue #29):
 * - زر الاتصال يسجّل **محاولة** `IN_APP` عبر آلة الحالات حصراً (AC-3) — لا احتفال
 *   مباشراً عند التسجيل، ولا كتابة لآخر تواصل (بوابة AC-8 في آلة الحالات).
 * - الاحتفال (كونفيتي) يُقرر عبر [CelebrateTransitionUseCase] على انتقال أحمر←أخضر
 *   مُكتشف بفرق انبعاثات القائمة في الذاكرة — أول انبعاث يبذّر الخريطة حصراً،
 *   والكونفيتي المعطّل في التفضيلات لا يُطلق أبداً (سياسة الاحتفال).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamViewModel(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val getDashboardStats: GetArhamDashboardStatsUseCase,
    private val recordDialAttempt: RecordDialAttemptUseCase,
    private val celebrateTransition: CelebrateTransitionUseCase,
    private val clock: AppClock
) : BaseMviViewModel<ArhamUiState, ArhamIntent, ArhamEffect>(ArhamUiState()) {

    private val periodFlow = MutableStateFlow(ArhamPeriod.WEEK)

    /** خريطة حالة كل جهة في آخر انبعاث — `null` قبل أول انبعاث (يبذّر بلا احتفال). */
    private var previousStatuses: Map<Long, ArhamStatus>? = null

    init {
        getPriorityList()
            .onEach { items -> detectCelebration(items) }
            .launchIn(viewModelScope)
    }

    override val state: StateFlow<ArhamUiState> = periodFlow
        .flatMapLatest { period ->
            combine(getPriorityList(), getDashboardStats(period)) { list, stats ->
                ArhamUiState(period = period, items = list, stats = stats)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ArhamUiState()
        )

    override fun onIntent(intent: ArhamIntent) {
        when (intent) {
            is ArhamIntent.ChangePeriod -> periodFlow.value = intent.period
            is ArhamIntent.RecordCall -> {
                viewModelScope.launch {
                    recordDialAttempt(
                        contactId = intent.contactId,
                        remoteNumber = intent.phone,
                        timestampMillis = clock.nowEpochMillis(),
                    )
                    sendEffect(ArhamEffect.Dial(intent.phone))
                }
            }
        }
    }

    /**
     * كاشف الانتقال أحمر←أخضر على انبعاثات القائمة في الذاكرة: الجهة الجديدة بلا
     * حالة سابقة لا تُحتفَل، والقرار للسياسة (كونفيتي مفعَّل + مرة واحدة في فترة
     * الأسبوع لكل جهة). أثر واحد كحدّ أقصى لكل انبعاث.
     */
    private fun detectCelebration(items: List<ArhamItem>) {
        val currentStatuses = items.associate { it.contactId to it.status }
        val previous = previousStatuses
        previousStatuses = currentStatuses
        if (previous == null) return
        viewModelScope.launch {
            for (item in items) {
                val before = previous[item.contactId] ?: continue
                if (before != item.status &&
                    celebrateTransition(item.contactId, previousStatus = before, currentStatus = item.status)
                ) {
                    sendEffect(ArhamEffect.ShowCelebrationConfetti)
                    return@launch
                }
            }
        }
    }
}
