package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class EventsExportMonitor(private val repository: EventsRepository, private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit, private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    private val state = MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs = state.asStateFlow()
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    private var active = false
    private var job: Job? = null
    private var generation = 0L

    fun watch(jobs: List<ExportJob>) {
        val new = jobs.filter { incoming -> state.value.none { it.id == incoming.id } }
        if (new.isEmpty()) return
        state.value += new
        restart()
    }

    fun setActive(active: Boolean) {
        if (active == this.active) return
        this.active = active
        restart()
    }

    fun retry() = restart()

    private fun restart() {
        generation++
        job?.cancel()
        if (!active || state.value.isEmpty()) return
        val token = generation
        job = scope.launch {
            do {
                try {
                    val current = repository.exports().associateBy { it.id }
                    if (token != generation || !active) return@launch
                    state.value = state.value.map { current[it.id] ?: it }
                    failure.value = false
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (token != generation || !active) return@launch
                    failure.value = true
                    if (error is FrigateHttpException && error.code == 401) { onUnauthorized(); return@launch }
                }
                if (state.value.none { it.status in setOf("queued", "running") }) break
                sleep(5_000)
            } while (isActive && active && token == generation)
        }
    }
}
