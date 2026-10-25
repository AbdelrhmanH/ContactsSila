package app.sila.feature.services.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.Service
import app.sila.feature.services.domain.ObserveServicesDirectoryUseCase
import app.sila.feature.services.domain.ServicesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ServicesUiState(
    val selectedProfessionId: String? = null,
    val searchQuery: String = "",
    val services: List<Service> = emptyList()
)

sealed interface ServicesIntent {
    data class SelectProfession(val professionId: String?) : ServicesIntent
    data class UpdateSearchQuery(val query: String) : ServicesIntent
    data class CallService(val phoneE164: String) : ServicesIntent
}

sealed interface ServicesEffect {
    data class OpenDialer(val phoneE164: String) : ServicesEffect
}

/**
 * ViewModel ميزة دليل الخدمات — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModel(
    private val observeServicesDirectory: ObserveServicesDirectoryUseCase
) : BaseMviViewModel<ServicesUiState, ServicesIntent, ServicesEffect>(ServicesUiState()) {

    constructor(repository: ServicesRepository) : this(
        observeServicesDirectory = ObserveServicesDirectoryUseCase(repository)
    )

    private val professionFlow = MutableStateFlow<String?>(null)
    private val searchFlow = MutableStateFlow("")

    override val state: StateFlow<ServicesUiState> = combine(professionFlow, searchFlow) { prof, q -> prof to q }
        .flatMapLatest { (prof, q) ->
            observeServicesDirectory(prof, q).map { list ->
                ServicesUiState(
                    selectedProfessionId = prof,
                    searchQuery = q,
                    services = list
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServicesUiState()
        )

    override fun onIntent(intent: ServicesIntent) {
        when (intent) {
            is ServicesIntent.SelectProfession -> {
                professionFlow.value = intent.professionId
            }
            is ServicesIntent.UpdateSearchQuery -> {
                searchFlow.value = intent.query
            }
            is ServicesIntent.CallService -> {
                sendEffect(ServicesEffect.OpenDialer(intent.phoneE164))
            }
        }
    }
}
