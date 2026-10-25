package app.sila.feature.arham.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppClock
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamPeriod
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.RecordArhamCallUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamViewModel(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val getDashboardStats: GetArhamDashboardStatsUseCase,
    private val recordArhamCall: RecordArhamCallUseCase
) : BaseMviViewModel<ArhamUiState, ArhamIntent, ArhamEffect>(ArhamUiState()) {

    constructor(
        repository: ArhamRepository,
        getPriorityList: GetArhamPriorityListUseCase,
        getDashboardStats: GetArhamDashboardStatsUseCase,
        clock: AppClock
    ) : this(
        getPriorityList = getPriorityList,
        getDashboardStats = getDashboardStats,
        recordArhamCall = RecordArhamCallUseCase(repository, clock)
    )

    private val periodFlow = MutableStateFlow(ArhamPeriod.WEEK)

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
                    recordArhamCall(intent.contactId)
                    sendEffect(ArhamEffect.Dial(intent.phone))
                    sendEffect(ArhamEffect.ShowCelebrationConfetti)
                }
            }
        }
    }
}
