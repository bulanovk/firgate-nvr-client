package com.frigate.android.feature.events

import com.frigate.android.core.server.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsControllerTest {
    private fun query(camera: String = "yard", day: String? = null) = EventsQuery(listOf(camera), EventsRange(100.0, 200.0, day), "UTC")
    private fun snapshot(id: String = "r", count: Int = 1) = EventsSnapshot(listOf(eventReview(id)), FrigateReviewSummary(FrigateReviewCounts(count, 0, 0, 0), emptyMap()))
    private fun controller(repo: FakeEventsRepository, unauthorized: () -> Unit = {}) = EventsController(
        repo, CoroutineScope(Dispatchers.Unconfined), query(), sleep = { awaitCancellation() }, onUnauthorized = unauthorized,
    )

    @Test
    fun resumeAdvancesRollingWindowButRetainsSelection() {
        var now = 200.0
        val queries = mutableListOf<EventsQuery>()
        val repo = FakeEventsRepository().apply { loadBlock = { queries += it; snapshot() } }
        val controller = EventsController(repo, CoroutineScope(Dispatchers.Unconfined), query(),
            sleep = { awaitCancellation() }, now = { now })
        controller.setActive(true)
        controller.toggleSelected("r")
        controller.setActive(false)
        now = 400.0
        controller.setActive(true)
        assertEquals(400.0, queries.last().range.before)
        assertEquals(400.0 - 86400, queries.last().range.after)
        assertEquals(setOf("r"), controller.state.value.selectedIds)
        controller.close()
    }

    @Test
    fun motionLoadsWithoutObjectFiltersAndHistoricalRangeDoesNotRoll() {
        val queries = mutableListOf<EventsQuery>()
        val repo = FakeEventsRepository().apply { loadBlock = { queries += it; snapshot(count = 12) } }
        val original = query(day = "2026-10-01").copy(labels = listOf("person"), zones = listOf("door"))
        val controller = EventsController(repo, CoroutineScope(Dispatchers.Unconfined), original,
            sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r")
        controller.setFilter(EventsFilter(type = EventsType.MOTION))
        assertTrue(queries.last().labels.isEmpty())
        assertTrue(queries.last().zones.isEmpty())
        assertEquals(original.range, queries.last().range)
        assertTrue(controller.state.value.selectedIds.isEmpty())
        assertFalse(controller.state.value.newItemsAvailable)
        controller.setFilter(EventsFilter())
        assertEquals(listOf("person"), queries.last().labels)
        assertFalse(controller.state.value.newItemsAvailable)
        controller.close()
    }

    @Test
    fun emptyListWithPositiveSummaryRetriesOnceWithoutShowingNewItemsBanner() {
        val repo = FakeEventsRepository()
        repo.loadBlock = { if (repo.loads == 1) snapshot(count = 2).copy(items = emptyList()) else snapshot() }
        val controller = controller(repo)
        controller.setActive(true)
        assertEquals(2, repo.loads)
        assertEquals("r", controller.state.value.items.single().id)
        assertFalse(controller.state.value.newItemsAvailable)
        controller.close()
    }

    @Test
    fun pollingWaitsThirtySecondsAndStopsWhenHidden() {
        val waits = kotlinx.coroutines.channels.Channel<CompletableDeferred<Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val durations = mutableListOf<Long>()
        val repo = FakeEventsRepository().apply { loadBlock = { snapshot() } }
        val controller = EventsController(repo, CoroutineScope(Dispatchers.Unconfined), query(), sleep = {
            durations += it
            val gate = CompletableDeferred<Unit>()
            waits.trySend(gate)
            gate.await()
        })
        controller.setActive(true)
        assertEquals(listOf(30000L), durations)
        waits.tryReceive().getOrThrow().complete(Unit)
        assertEquals(2, repo.loads)
        controller.setActive(false)
        waits.tryReceive().getOrThrow().complete(Unit)
        assertEquals(2, repo.loads)
        controller.close()
    }

    @Test
    fun detectionSocketUpdatesAreCoalescedAndReconciledOverRest() {
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        val repo = FakeEventsRepository().apply { loadBlock = { snapshot() } }
        val controller = EventsController(repo, CoroutineScope(Dispatchers.Unconfined), query(), sleep = {
            val gate = CompletableDeferred<Unit>()
            waits += it to gate
            gate.await()
        })
        controller.setActive(true)
        val update = """{"topic":"reviews","payload":{"type":"end","after":{"severity":"detection"}}}"""
        controller.onReviewsMessage(update)
        controller.onReviewsMessage(update)
        assertEquals(listOf(30000L, 1000L), waits.map { it.first })
        waits.last().second.complete(Unit)
        assertEquals(2, repo.loads)
        controller.close()
    }

    @Test
    fun staleGenerationCannotCommit() {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeEventsRepository()
        repo.loadBlock = { q -> if (q.cameraIds == listOf("yard")) { withContext(NonCancellable) { gate.await() }; snapshot("old") } else snapshot("new") }
        val controller = controller(repo)
        controller.setActive(true)
        controller.setQuery(query("street"))
        assertEquals("new", controller.state.value.items.single().id)
        gate.complete(Unit)
        assertEquals("new", controller.state.value.items.single().id)
        controller.close()
    }

    @Test
    fun summaryBannerDoesNotReplaceScrolledListUntilRequested() {
        val repo = FakeEventsRepository()
        repo.loadBlock = { snapshot("old") }
        val controller = controller(repo)
        controller.setActive(true)
        repo.loadBlock = { EventsSnapshot(listOf(eventReview("new"), eventReview("old")), FrigateReviewSummary(FrigateReviewCounts(2, 0, 0, 0), emptyMap())) }
        controller.onReconnect()
        assertEquals(listOf("old"), controller.state.value.items.map { it.id })
        assertTrue(controller.state.value.newItemsAvailable)
        controller.refresh()
        assertEquals(listOf("new", "old"), controller.state.value.items.map { it.id })
        assertFalse(controller.state.value.newItemsAvailable)
        controller.close()
    }

    @Test
    fun failedRefreshKeepsDataAndUnauthorizedReachesSessionOwner() {
        val repo = FakeEventsRepository()
        repo.loadBlock = { snapshot() }
        var renewals = 0
        val controller = controller(repo) { renewals++ }
        controller.setActive(true)
        repo.loadBlock = { throw FrigateHttpException(401, "Expired") }
        controller.refresh()
        assertEquals("r", controller.state.value.items.single().id)
        assertTrue(controller.state.value.refreshFailed)
        assertEquals(1, renewals)
        controller.close()
    }

    @Test
    fun stopCancelsReconciliationAndCancellationIsNotUiError() {
        val repo = FakeEventsRepository()
        repo.loadBlock = { awaitCancellation() }
        val controller = controller(repo)
        controller.setActive(true)
        controller.setActive(false)
        assertFalse(controller.state.value.refreshFailed)
        assertFalse(controller.state.value.loading)
        val calls = repo.loads
        controller.onReconnect()
        assertEquals(calls, repo.loads)
        controller.close()
    }

    @Test
    fun filterChangeIsLocalAndMutationUpdatesCountsAndSelection() {
        val repo = FakeEventsRepository()
        repo.loadBlock = { snapshot() }
        val controller = controller(repo)
        controller.setActive(true)
        controller.toggleSelected("r")
        controller.applyMutation(listOf("r"), true, false)
        assertTrue(controller.state.value.selectedIds.isEmpty())
        assertTrue(filteredReviews(controller.state.value.items, controller.state.value.filter).isEmpty())
        assertEquals(0 to 0, reviewCounts(controller.state.value.summary!!, null, false))
        val calls = repo.loads
        controller.setFilter(EventsFilter(showReviewed = true))
        assertEquals(calls, repo.loads)
        assertEquals(listOf("r"), filteredReviews(controller.state.value.items, controller.state.value.filter).map { it.id })
        controller.close()
    }
}

internal class FakeEventsRepository : EventsRepository {
    var loads = 0
    var loadBlock: suspend (EventsQuery) -> EventsSnapshot = { EventsSnapshot(emptyList(), FrigateReviewSummary(null, emptyMap())) }
    override suspend fun load(query: EventsQuery): EventsSnapshot { loads++; return loadBlock(query) }
    var marks = mutableListOf<Pair<List<String>, Boolean>>()
    var markFailure: Exception? = null
    override suspend fun markViewed(ids: List<String>, reviewed: Boolean) { markFailure?.let { throw it }; marks += ids to reviewed }
    override suspend fun delete(ids: List<String>) = Unit
    override suspend fun previews(cameraIds: List<String>, range: EventsRange): List<ReviewPreview> = emptyList()
    override suspend fun motion(query: EventsQuery): List<MotionPoint> = emptyList()
    override suspend fun recordings(cameraId: String, range: EventsRange): List<MotionRecording> = emptyList()
    override suspend fun previewFrames(cameraId: String, range: EventsRange): List<PreviewFrame> = emptyList()
    override suspend fun exportSingle(review: FrigateReview, now: Double) = ExportJob("job", "queued")
    override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?) = ExportBatchResult(emptyMap(), emptyMap())
    override suspend fun exports(): List<ExportJob> = emptyList()
    override suspend fun exportCases(): List<ExportCase> = emptyList()
}
