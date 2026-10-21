package app.musikbox.discover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUi(
    val loading: Boolean = false,
    val result: DiscoverResult? = null,
    val error: String? = null,
)

/** App-wide Discover state, so the mix survives navigation and is rebuilt only when stale or asked. */
class DiscoverModel(private val engine: DiscoverEngine, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DiscoverUi())
    val state: StateFlow<DiscoverUi> = _state.asStateFlow()
    private var job: Job? = null

    /** Build if there is nothing yet or the mix is older than [maxAgeMs]. */
    fun ensureFresh(maxAgeMs: Long = 6 * 60 * 60_000L) {
        val r = _state.value.result
        if (job?.isActive == true) return
        if (r == null || System.currentTimeMillis() - r.generatedAt > maxAgeMs) regenerate()
    }

    fun regenerate() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            runCatching { engine.build(seed = System.nanoTime()) }
                .onSuccess { r -> _state.value = DiscoverUi(loading = false, result = r) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach the server") } }
        }
    }

    fun clear() { job?.cancel(); _state.value = DiscoverUi() }
}
