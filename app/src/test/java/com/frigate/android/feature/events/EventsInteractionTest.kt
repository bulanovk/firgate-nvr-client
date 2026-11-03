package com.frigate.android.feature.events

import com.frigate.android.core.server.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsInteractionTest {
    @Test
    fun markingWaitsForSuccessAndFailureKeepsSelectionForRetry() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var reject = true
        val base = FakeEventsRepository().apply { loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) } }
        val repo = object : EventsRepository by base {
            override suspend fun markViewed(ids: List<String>, reviewed: Boolean) { gate.await(); if (reject) throw java.io.IOException("Offline") }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r")
        var invalidations = 0
        val interaction = EventsInteraction(controller, repo, scope, {}, onMutation = { invalidations++ })
        interaction.dispatch(EventsAction.MarkViewed(listOf("r"), true))
        assertEquals(0, invalidations)
        assertEquals(setOf("r"), controller.state.value.pendingIds)
        assertFalse(controller.state.value.items.single().hasBeenReviewed)
        gate.complete(Unit)
        assertEquals(setOf("r"), controller.state.value.selectedIds)
        assertNotNull(controller.state.value.message)
        assertEquals(0, invalidations)
        reject = false
        interaction.dispatch(EventsAction.MarkViewed(listOf("r"), true))
        assertTrue(controller.state.value.items.single().hasBeenReviewed)
        assertTrue(controller.state.value.pendingIds.isEmpty())
        assertTrue(controller.state.value.selectedIds.isEmpty())
        assertEquals(1, invalidations)
        scope.cancel()
    }

    @Test
    fun destructiveActionRequiresConfirmationAndAdminAndExportRetainsFailures() {
        val deleted = mutableListOf<String>()
        val base = FakeEventsRepository().apply { loadBlock = { EventsSnapshot(listOf(eventReview("r"), eventReview("failed")), FrigateReviewSummary(null, emptyMap())) } }
        val repo = object : EventsRepository by base {
            override suspend fun delete(ids: List<String>) { deleted += ids }
            override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?) =
                ExportBatchResult(mapOf("r" to ExportJob("job", "queued")), mapOf("failed" to "Unavailable"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        val interaction = EventsInteraction(controller, repo, scope, {})
        controller.toggleSelected("r"); controller.toggleSelected("failed")
        interaction.dispatch(EventsAction.ExportSelection)
        interaction.confirmExport(200.0, null)
        assertEquals(setOf("failed"), controller.state.value.selectedIds)
        assertEquals("queued", interaction.exportJobs.value.single().status)
        interaction.dispatch(EventsAction.DeleteSelection)
        assertTrue(deleted.isEmpty())
        controller.toggleSelected("r")
        interaction.confirmDelete("viewer")
        assertTrue(deleted.isEmpty())
        interaction.confirmDelete("admin")
        assertEquals(listOf("failed"), deleted)
        assertEquals(listOf("r"), controller.state.value.items.map { it.id })
        scope.cancel()
    }

    @Test
    fun singleExportQueuesImmediatelyWithoutConfirmation() {
        val exported = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun exportSingle(review: FrigateReview, now: Double): ExportJob {
                exported += review.id
                gate.await()
                return ExportJob("job", "queued")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r")
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.ExportSelection)
        assertEquals(listOf("r"), exported)
        assertFalse(interaction.exportRequested.value)
        assertEquals(setOf("r"), controller.state.value.pendingIds)
        interaction.dispatch(EventsAction.ExportSelection)
        assertEquals(1, exported.size)
        gate.complete(Unit)
        assertEquals("job", interaction.exportJobs.value.single().id)
        assertTrue(controller.state.value.selectedIds.isEmpty())
        scope.cancel()
    }

    @Test
    fun failedSingleExportKeepsSelectionForExplicitRetry() {
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) }
        }
        var reject = true
        val repo = object : EventsRepository by base {
            override suspend fun exportSingle(review: FrigateReview, now: Double): ExportJob {
                if (reject) throw java.io.IOException("Offline")
                return ExportJob("job", "queued")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r")
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.ExportSelection)
        assertFalse(interaction.exportRequested.value)
        assertEquals(setOf("r"), controller.state.value.selectedIds)
        assertTrue(controller.state.value.pendingIds.isEmpty())
        reject = false
        interaction.dispatch(EventsAction.ExportSelection)
        assertFalse(interaction.exportRequested.value)
        assertEquals("job", interaction.exportJobs.value.single().id)
        scope.cancel()
    }

    @Test
    fun failedBatchExportKeepsConfirmationAndDoesNotSubmitUntilConfirmed() {
        var calls = 0
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r"), eventReview("other")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult {
                calls++
                return ExportBatchResult(emptyMap(), reviews.associate { it.id to "Offline" })
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r"); controller.toggleSelected("other")
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.ExportSelection)
        assertEquals(0, calls)
        assertTrue(interaction.exportRequested.value)
        interaction.confirmExport(200.0, null)
        assertEquals(1, calls)
        assertTrue(interaction.exportRequested.value)
        assertEquals(setOf("r", "other"), controller.state.value.selectedIds)
        interaction.confirmExport(200.0, null)
        assertEquals(2, calls)
        scope.cancel()
    }

    @Test
    fun retryClearsPreviousErrorSoAnotherFailureCanBePresented() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun markViewed(ids: List<String>, reviewed: Boolean) {
                if (++calls == 2) gate.await()
                throw java.io.IOException("Offline")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, "1970-01-01"), "UTC"), sleep = { awaitCancellation() })
            controller.setActive(true)
            val interaction = EventsInteraction(controller, repo, scope, {})
            interaction.dispatch(EventsAction.MarkViewed(listOf("r"), true))
            assertNotNull(controller.state.value.message)
            interaction.dispatch(EventsAction.RetryFailedAction)
            assertEquals(setOf("r"), controller.state.value.pendingIds)
            assertNull(controller.state.value.message)
            gate.complete(Unit)
            assertNotNull(controller.state.value.message)
            assertTrue(interaction.canRetry)
        } finally { scope.cancel() }
    }

    @Test
    fun explicitRetryRepeatsFailedMarkRatherThanOnlyReloadingReviews() {
        var calls = 0
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun markViewed(ids: List<String>, reviewed: Boolean) {
                calls++
                if (calls == 1) throw java.io.IOException("Offline")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, "1970-01-01"), "UTC"), sleep = { awaitCancellation() })
            controller.setActive(true)
            val interaction = EventsInteraction(controller, repo, scope, {})
            interaction.dispatch(EventsAction.MarkViewed(listOf("r"), true))
            assertTrue(interaction.canRetry)
            interaction.dispatch(EventsAction.RetryFailedAction)
            assertEquals(2, calls)
            assertTrue(controller.state.value.items.single().hasBeenReviewed)
            assertFalse(interaction.canRetry)
        } finally { scope.cancel() }
    }

    @Test
    fun queryChangeCannotReopenExportWhilePreviousSubmissionIsPending() {
        val gate = CompletableDeferred<Unit>()
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r"), eventReview("other")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult {
                gate.await()
                return ExportBatchResult(reviews.associate { it.id to ExportJob("job-${it.id}", "queued") }, emptyMap())
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, "1970-01-01"), "UTC"), sleep = { awaitCancellation() })
            controller.setActive(true)
            val interaction = EventsInteraction(controller, repo, scope, {})
            controller.toggleSelected("r"); controller.toggleSelected("other")
            interaction.dispatch(EventsAction.ExportSelection)
            interaction.confirmExport(200.0, null)
            interaction.dispatch(EventsAction.SetCameras(listOf("street")))
            assertEquals(setOf("r", "other"), controller.state.value.pendingIds)
            controller.toggleSelected("r"); controller.toggleSelected("other")
            interaction.dispatch(EventsAction.ExportSelection)
            assertFalse(interaction.exportRequested.value)
            gate.complete(Unit)
            assertTrue(controller.state.value.pendingIds.isEmpty())
            assertEquals(setOf("r", "other"), controller.state.value.selectedIds)
            interaction.dispatch(EventsAction.ExportSelection)
            assertTrue(interaction.exportRequested.value)
        } finally { scope.cancel() }
    }

    @Test
    fun queryChangeDismissesUnconfirmedMutations() {
        var calls = 0
        val base = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r"), eventReview("other")), FrigateReviewSummary(null, emptyMap())) }
        }
        val repo = object : EventsRepository by base {
            override suspend fun delete(ids: List<String>) { calls++ }
            override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult {
                calls++
                return ExportBatchResult(emptyMap(), emptyMap())
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        val interaction = EventsInteraction(controller, repo, scope, {})
        controller.toggleSelected("r"); controller.toggleSelected("other")
        interaction.dispatch(EventsAction.ExportSelection)
        interaction.dispatch(EventsAction.DeleteSelection)
        interaction.dispatch(EventsAction.SetCameras(listOf("driveway")))
        assertFalse(interaction.exportRequested.value)
        assertFalse(interaction.deleteRequested.value)
        interaction.confirmExport(200.0, null)
        interaction.confirmDelete("admin")
        assertEquals(0, calls)
        if ("r" !in controller.state.value.selectedIds) controller.toggleSelected("r")
        if ("other" !in controller.state.value.selectedIds) controller.toggleSelected("other")
        interaction.dispatch(EventsAction.ExportSelection)
        interaction.dispatch(EventsAction.DeleteSelection)
        assertTrue(interaction.exportRequested.value)
        assertTrue(interaction.deleteRequested.value)
        interaction.dispatch(EventsAction.SelectType(EventsType.DETECTION))
        assertFalse(interaction.exportRequested.value)
        assertFalse(interaction.deleteRequested.value)
        scope.cancel()
    }

    @Test
    fun confirmationCountsBelongToCapturedTargetsNotChangingSelection() {
        val repo = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r"), eventReview("other")), FrigateReviewSummary(null, emptyMap())) }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,"1970-01-01"),"UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        controller.toggleSelected("r"); controller.toggleSelected("other")
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.ExportSelection)
        interaction.dispatch(EventsAction.DeleteSelection)
        controller.toggleSelected("other")
        assertEquals(2, interaction.exportCount)
        assertEquals(2, interaction.deleteCount)
        scope.cancel()
    }

    @Test
    fun filterActionAppliesLabelsZonesAndSeverityInOneDispatch() {
        val repo = FakeEventsRepository().apply {
            loadBlock = { EventsSnapshot(listOf(eventReview("r")), FrigateReviewSummary(null, emptyMap())) }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, "1970-01-01"), "UTC"), sleep = { awaitCancellation() })
        controller.setActive(true)
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.SetFilters(listOf("person"), listOf("porch"), true))
        assertEquals(listOf("person"), controller.state.value.query.labels)
        assertEquals(listOf("porch"), controller.state.value.query.zones)
        assertTrue(controller.state.value.filter.includeOtherSeverity)
        scope.cancel()
    }

    @Test
    fun typeAndQueryChangesClosePreviewAndNeverMutateOnDeleteIntent() {
        val repo = FakeEventsRepository()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller = EventsController(repo, scope, EventsQuery(listOf("yard"), EventsRange(100.0,200.0,null),"UTC"))
        val interaction = EventsInteraction(controller, repo, scope, {})
        interaction.dispatch(EventsAction.Preview("r"))
        assertEquals("r", interaction.previewId.value)
        interaction.dispatch(EventsAction.SelectType(EventsType.DETECTION))
        assertNull(interaction.previewId.value)
        interaction.dispatch(EventsAction.DeleteSelection)
        assertFalse(interaction.deleteRequested.value)
        scope.cancel()
    }
}
