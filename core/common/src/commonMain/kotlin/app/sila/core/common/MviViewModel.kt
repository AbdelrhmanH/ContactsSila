package app.sila.core.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * عقد MVI الموحد لجميع الـ ViewModels في صِلَة (ARCHITECTURE.md §4).
 */
interface MviViewModel<S : Any, I : Any, E : Any> {
    val state: StateFlow<S>
    val effects: Flow<E>
    fun onIntent(intent: I)
}

/**
 * الصنف الأساسي المشترك لبناء ViewModels تتبع نمط MVI + UDF.
 */
abstract class BaseMviViewModel<S : Any, I : Any, E : Any>(initialState: S) : ViewModel(), MviViewModel<S, I, E> {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    override val effects: Flow<E> = _effects.receiveAsFlow()

    protected val currentState: S
        get() = _state.value

    protected fun updateState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    protected fun sendEffect(effect: E) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }
}
