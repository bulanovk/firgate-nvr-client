package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.FrigateReviewCounts
import com.frigate.android.core.server.FrigateReviewSummary
import com.frigate.android.feature.review.ReviewUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class EventsUiState(
    val query: EventsQuery,
    val filter: EventsFilter = EventsFilter(),
    val items: List<FrigateReview> = emptyList(),
    val summary: FrigateReviewSummary? = null,
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val refreshFailed: Boolean = false,
    val newItemsAvailable: Boolean = false,
    val pendingIds: Set<String> = emptySet(),
    val message: String? = null,
)

/** REST is authoritative. Socket messages invalidate it; background refresh never jumps the grid. */
internal class EventsController(
    private val repository: EventsRepository,
    private val scope: CoroutineScope,
    query: EventsQuery,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onUnauthorized: () -> Unit = {},
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val mutableState = MutableStateFlow(EventsUiState(query))
    val state: StateFlow<EventsUiState> = mutableState.asStateFlow()
    private var active = false
    private var generation = 0L
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var socketJob: Job? = null
    private var socketDelay: Long? = null
    private var latest: EventsSnapshot? = null

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) {
            invalidateLoad()
            pollJob?.cancel()
            socketJob?.cancel()
            socketDelay = null
            mutableState.update { it.copy(loading = false) }
            return
        }
        load(explicit = !state.value.loaded)
        pollJob = scope.launch {
            while (isActive) {
                sleep(30_000)
                if (!this@EventsController.active) break
                load(explicit = false)
            }
        }
    }

    fun setQuery(query: EventsQuery) {
        if (query == state.value.query) return
        invalidateLoad()
        latest = null
        mutableState.value = EventsUiState(query, state.value.filter, pendingIds = state.value.pendingIds)
        if (active) load(explicit = true)
    }

    fun setFilter(filter: EventsFilter) {
        val motionChanged = (state.value.filter.type == EventsType.MOTION) != (filter.type == EventsType.MOTION)
        mutableState.update { it.copy(filter = filter, selectedIds = emptySet()) }
        if (motionChanged && active) load(explicit = true) else updateBanner()
    }

    fun refresh() { if (active) load(explicit = true) }
    fun onReconnect() { if (active) load(explicit = false) }

    fun onReviewsMessage(raw: String) {
        val update = ReviewUpdates.parseUpdate(raw) ?: return
        if (update.afterSeverity !in setOf("alert", "detection", "significant_motion")) return
        // Reuse the carousel cadence for every Events severity, not its alerts-only gate.
        val wait = ReviewUpdates.delayFor(update.copy(afterSeverity = "alert")) ?: return
        if (!active) return
        if (socketJob?.isActive == true && (socketDelay ?: Long.MAX_VALUE) <= wait) return
        socketJob?.cancel()
        socketDelay = wait
        socketJob = scope.launch {
            sleep(wait)
            socketDelay = null
            if (active) load(explicit = false)
        }
    }

    fun toggleSelected(id: String) {
        if (state.value.items.none { it.id == id }) return
        mutableState.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }
    fun clearSelection() { mutableState.update { it.copy(selectedIds = emptySet()) } }
    fun setPending(ids: List<String>, pending: Boolean) {
        mutableState.update { it.copy(pendingIds = if (pending) it.pendingIds + ids else it.pendingIds - ids.toSet()) }
    }
    fun showMessage(message: String?) { mutableState.update { it.copy(message = message) } }

    fun applyMutation(ids: List<String>, reviewed: Boolean?, deleted: Boolean) {
        invalidateLoad()
        val changed = state.value.items.filter { it.id in ids }
        fun adjust(summary: FrigateReviewSummary?): FrigateReviewSummary? {
            if (summary == null) return null
            fun count(value: FrigateReviewCounts): FrigateReviewCounts {
                var result = value
                changed.forEach { item ->
                    val totalDelta = if (deleted) -1 else 0
                    val seenDelta = if (deleted) { if (item.hasBeenReviewed) -1 else 0 }
                        else if (reviewed != null && reviewed != item.hasBeenReviewed) { if (reviewed) 1 else -1 } else 0
                    result = when (item.severity) {
                        "alert" -> result.copy(totalAlert = (result.totalAlert + totalDelta).coerceAtLeast(0), reviewedAlert = (result.reviewedAlert + seenDelta).coerceAtLeast(0))
                        "detection" -> result.copy(totalDetection = (result.totalDetection + totalDelta).coerceAtLeast(0), reviewedDetection = (result.reviewedDetection + seenDelta).coerceAtLeast(0))
                        else -> result
                    }
                }
                return result
            }
            val key = state.value.query.range.dayKey
            return if (key == null) summary.copy(last24Hours = summary.last24Hours?.let(::count)) else
                summary.copy(days = summary.days.mapValues { (day, value) -> if (day == key) count(value) else value })
        }
        mutableState.update { current -> current.copy(
            items = current.items.mapNotNull { item -> if (item.id !in ids) item else if (deleted) null else item.copy(hasBeenReviewed = reviewed ?: item.hasBeenReviewed) },
            selectedIds = current.selectedIds - ids.toSet(), summary = adjust(current.summary), loading = false,
        ) }
        latest = null
    }

    fun close() {
        active = false
        invalidateLoad()
        pollJob?.cancel()
        socketJob?.cancel()
    }

    private fun invalidateLoad() { generation++; loadJob?.cancel(); loadJob = null }

    private fun load(explicit: Boolean, retryEmpty: Boolean = true) {
        invalidateLoad()
        val token = generation
        val current = state.value
        val range = if (current.query.range.dayKey == null) eventsRange(null, current.query.timezone, now()) else current.query.range
        val query = current.query.copy(range = range).let {
            if (current.filter.type == EventsType.MOTION) it.copy(labels = emptyList(), zones = emptyList()) else it
        }
        mutableState.update { it.copy(query = it.query.copy(range = range), loading = true, refreshFailed = false, message = null) }
        loadJob = scope.launch {
            try {
                val snapshot = repository.load(query)
                if (token != generation || !active) return@launch
                latest = snapshot
                mutableState.update { current ->
                    val replace = explicit || !current.loaded || current.items.isEmpty()
                    val oldIds = current.items.map { it.id }.toSet()
                    val items = if (replace || query.range.dayKey != null) snapshot.items else snapshot.items.filter { it.id in oldIds }
                    current.copy(items = items, summary = snapshot.summary, loading = false, loaded = true,
                        selectedIds = current.selectedIds.intersect(items.map { it.id }.toSet()), refreshFailed = false)
                }
                updateBanner()
                val counts = reviewCounts(snapshot.summary, query.range.dayKey, state.value.filter.showReviewed)
                if (retryEmpty && query.range.dayKey == null && state.value.filter.type != EventsType.MOTION &&
                    snapshot.items.isEmpty() && counts.first + counts.second > 0) {
                    load(explicit = true, retryEmpty = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token != generation || !active) return@launch
                if (error is FrigateHttpException && error.code == 401) onUnauthorized()
                mutableState.update { it.copy(loading = false, refreshFailed = true, message = error.message ?: "Could not load events") }
            }
        }
    }

    private fun updateBanner() {
        mutableState.update { current ->
            val counts = current.summary?.let { reviewCounts(it, current.query.range.dayKey, current.filter.showReviewed) }
            val expected = when (current.filter.type) {
                EventsType.ALERT -> if (current.filter.includeOtherSeverity) counts?.let { it.first + it.second } else counts?.first
                EventsType.DETECTION -> if (current.filter.includeOtherSeverity) counts?.let { it.first + it.second } else counts?.second
                EventsType.MOTION -> null
            } ?: 0
            val shown = filteredReviews(current.items, current.filter).size
            current.copy(newItemsAvailable = current.query.range.dayKey == null && !current.loading && !current.refreshFailed &&
                shown > 0 && shown < expected)
        }
    }
}
