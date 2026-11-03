package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ReviewRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ReviewUiState(
    val items: List<FrigateReview> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val refreshFailed: Boolean = false,
    val markingIds: Set<String> = emptySet(),
    val markFailedIds: Set<String> = emptySet(),
)

/**
 * How often the carousel is reconciled with the server while the Live screen is visible.
 *
 * The list holds the alerts of the last hour, so a card has to leave the strip when its event
 * becomes older than an hour. The websocket only announces the changes it knows about, the timer
 * asks the server for the list again.
 */
internal const val REVIEW_RECONCILE_MILLIS = 30_000L

/**
 * Session scoped state of the Live review carousel. The list itself comes from REST; the shared
 * activity websocket only signals that it changed.
 */
internal class ReviewController(
    private val repository: ReviewRepository,
    private val scope: CoroutineScope,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onUnauthorized: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val scheduler = ReviewRefreshScheduler(nowMillis)
    private val viewedIds = mutableSetOf<String>()
    private var refreshJob: Job? = null
    private var reconcileJob: Job? = null
    private var active = false
    private var cameraIds: Set<String> = emptySet()
    private var generation = 0

    fun setActive(active: Boolean, cameraIds: Set<String>) {
        this.active = active
        this.cameraIds = cameraIds
        if (!active) {
            generation++
            scheduler.clear()
            refreshJob?.cancel()
            refreshJob = null
            reconcileJob?.cancel()
            reconcileJob = null
            return
        }
        scheduler.schedule(0L)
        ensureRefreshLoop()
        ensureReconcileLoop()
    }

    fun invalidate(delayMillis: Long = 0L) {
        if (!active) return
        scheduler.schedule(delayMillis)
        ensureRefreshLoop()
    }

    fun onReviewsMessage(raw: String) {
        val delay = ReviewUpdates.refreshDelayMillis(raw) ?: return
        invalidate(delay)
    }

    fun markViewed(id: String) {
        val current = _state.value
        if (id in current.markingIds) return
        _state.update { it.copy(markingIds = it.markingIds + id, markFailedIds = it.markFailedIds - id) }
        scope.launch {
            try {
                repository.markReviewsViewed(listOf(id))
                viewedIds += id
                _state.update { state ->
                    state.copy(
                        items = state.items.filterNot { it.id == id },
                        markingIds = state.markingIds - id,
                    )
                }
            } catch (error: Exception) {
                _state.update { state ->
                    state.copy(markingIds = state.markingIds - id, markFailedIds = state.markFailedIds + id)
                }
            }
        }
    }

    fun close() {
        generation++
        active = false
        scheduler.clear()
        refreshJob?.cancel()
        refreshJob = null
        reconcileJob?.cancel()
        reconcileJob = null
    }

    private fun ensureRefreshLoop() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                val remaining = scheduler.remainingMillis() ?: break
                if (remaining > 0L) sleep(remaining)
                if (!active) break
                if (!scheduler.takeDue()) continue
                load()
            }
        }
    }

    /**
     * Asks the server for the list again every half minute.
     *
     * The loop runs beside the refresh loop of the websocket: a refresh that is announced by the
     * server must not wait for the timer.
     */
    private fun ensureReconcileLoop() {
        if (reconcileJob?.isActive == true) return
        reconcileJob = scope.launch {
            while (isActive) {
                sleep(REVIEW_RECONCILE_MILLIS)
                if (!active) break
                load()
            }
        }
    }

    private suspend fun load() {
        val generation = this.generation
        val ids = cameraIds.toList()
        if (ids.isEmpty()) {
            _state.update { it.copy(items = emptyList(), loading = false, loaded = true, refreshFailed = false) }
            return
        }

        _state.update { it.copy(loading = true) }
        try {
            val reviews = repository.loadReviews(ids, severity = "alert", reviewed = 0, limit = 10)
            if (generation != this.generation) return
            val alerts = unreviewedAlerts(reviews, cameraIds, nowSeconds()).filterNot { it.id in viewedIds }
            _state.update { it.copy(items = alerts, loading = false, loaded = true, refreshFailed = false) }
        } catch (error: FrigateHttpException) {
            if (generation != this.generation) return
            if (error.code == 401) onUnauthorized()
            _state.update { it.copy(loading = false, loaded = true, refreshFailed = true) }
        } catch (error: Exception) {
            if (generation != this.generation) return
            _state.update { it.copy(loading = false, loaded = true, refreshFailed = true) }
        }
    }
}
