package com.frigate.android.feature.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** User intent is separate from confirmed server mutations. */
internal class EventsInteraction(
    private val controller: EventsController,
    repository: EventsRepository,
    private val scope: CoroutineScope,
    onUnauthorized: () -> Unit,
    private val onMutation: () -> Unit = {},
) {
    private val actions = EventsActions(repository, onUnauthorized)
    private val preview = MutableStateFlow<String?>(null)
    val previewId = preview.asStateFlow()
    private val delete = MutableStateFlow(false)
    val deleteRequested = delete.asStateFlow()
    private val export = MutableStateFlow(false)
    val exportRequested = export.asStateFlow()
    private val jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    private var deleteIds = emptyList<String>()
    private var exportReviews = emptyList<com.frigate.android.core.server.FrigateReview>()
    val exportJobs = jobs.asStateFlow()
    val deleteCount: Int get() = deleteIds.size
    val exportCount: Int get() = exportReviews.size
    private var failedAction: Pair<EventsQuery, () -> Unit>? = null
    val canRetry: Boolean get() = failedAction?.first == controller.state.value.query

    fun confirmDelete(role: String) {
        if (role != "admin" || !delete.value || controller.state.value.pendingIds.isNotEmpty()) return
        val state = controller.state.value
        val ids = deleteIds
        if (ids.isEmpty()) return
        delete.value = false
        controller.setPending(ids, true)
        scope.launch {
            try {
                actions.delete(ids)
                onMutation()
                if (controller.state.value.query == state.query) controller.applyMutation(ids, null, deleted = true)
                else controller.refresh()
                controller.showMessage(null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { controller.showMessage("Could not delete events. Please retry.") }
            finally { controller.setPending(ids, false) }
        }
    }

    fun confirmExport(now: Double, case: ExportCaseChoice?) {
        if (!export.value) return
        startExport(now, case)
    }

    private fun startExport(now: Double, case: ExportCaseChoice?) {
        if (controller.state.value.pendingIds.isNotEmpty()) return
        val state = controller.state.value
        val selected = exportReviews
        if (selected.isEmpty()) return
        if (selected.size > 50) { controller.showMessage("Select at most 50 events to export."); return }
        val ids = selected.map { it.id }
        controller.setPending(ids, true)
        scope.launch {
            try {
                val result = actions.export(selected, now, case)
                if (result.succeeded.isNotEmpty()) export.value = false
                jobs.value = (jobs.value + result.succeeded.values).distinctBy { it.id }
                if (controller.state.value.query == state.query) result.succeeded.keys.forEach { id ->
                    if (id in controller.state.value.selectedIds) controller.toggleSelected(id)
                }
                controller.showMessage(if (result.failed.isEmpty()) "Export queued."
                    else "${result.failed.size} exports failed. Failed events remain selected for retry.")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { controller.showMessage("Could not export events. Please retry.") }
            finally { controller.setPending(ids, false) }
        }
    }

    fun dispatch(action: EventsAction) {
        val state = controller.state.value
        when (action) {
            is EventsAction.Preview -> preview.value = action.id
            is EventsAction.SelectType -> {
                preview.value = null; dismissDelete(); dismissExport()
                controller.setFilter(state.filter.copy(type = action.type))
            }
            is EventsAction.SetCameras -> changeQuery(state.query.copy(cameraIds = action.ids))
            is EventsAction.SetDate -> changeQuery(state.query.copy(range = eventsRange(action.dayKey, state.query.timezone, System.currentTimeMillis() / 1000.0)))
            is EventsAction.SetReviewedFilter -> controller.setFilter(state.filter.copy(showReviewed = action.reviewed))
            is EventsAction.SetFilters -> {
                controller.setFilter(state.filter.copy(includeOtherSeverity = action.includeOtherSeverity))
                changeQuery(state.query.copy(labels = action.labels, zones = action.zones))
            }
            is EventsAction.ToggleSelection -> { preview.value = null; controller.toggleSelected(action.id) }
            EventsAction.ClearSelection -> controller.clearSelection()
            is EventsAction.MarkViewed -> mark(action.ids, action.reviewed)
            EventsAction.RetryFailedAction -> {
                if (state.pendingIds.isNotEmpty()) return
                val retry = failedAction?.takeIf { it.first == state.query }?.second
                failedAction = null
                if (retry != null) retry() else controller.refresh()
            }
            EventsAction.ReloadNewItems -> { preview.value = null; controller.refresh() }
            EventsAction.ExportSelection -> {
                if (state.pendingIds.isNotEmpty()) return
                exportReviews = state.items.filter { it.id in state.selectedIds }
                export.value = exportReviews.size > 1
                if (exportReviews.size == 1) startExport(System.currentTimeMillis() / 1000.0, null)
            }
            EventsAction.DeleteSelection -> {
                deleteIds = state.selectedIds.toList()
                delete.value = deleteIds.isNotEmpty()
            }
        }
    }

    fun closePreview() { preview.value = null }
    fun dismissDelete() { delete.value = false }
    fun dismissExport() { export.value = false }

    private fun changeQuery(query: EventsQuery) {
        preview.value = null; dismissDelete(); dismissExport()
        controller.setQuery(query)
    }

    private fun mark(ids: List<String>, reviewed: Boolean) {
        if (ids.isEmpty() || controller.state.value.pendingIds.isNotEmpty()) return
        val query = controller.state.value.query
        failedAction = null
        controller.showMessage(null)
        controller.setPending(ids, true)
        scope.launch {
            try {
                actions.mark(ids, reviewed)
                onMutation()
                if (controller.state.value.query == query) controller.applyMutation(ids, reviewed, deleted = false)
                else controller.refresh()
                controller.showMessage(null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                failedAction = query to { mark(ids, reviewed) }
                controller.showMessage("Could not update events. Please retry.")
            }
            finally { controller.setPending(ids, false) }
        }
    }
}
