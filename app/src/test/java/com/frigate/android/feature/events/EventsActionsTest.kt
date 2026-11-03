package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsActionsTest {
    @Test
    fun emptyDoesNotRequestAndMixedBecomesReviewedButAllReviewedBecomesUnreviewed() = runBlocking {
        val repo = FakeEventsRepository()
        val actions = EventsActions(repo, {})
        actions.mark(emptyList())
        assertTrue(repo.marks.isEmpty())
        actions.mark(listOf(eventReview("r"), eventReview("seen").copy(hasBeenReviewed = true)))
        actions.mark(listOf(eventReview("seen").copy(hasBeenReviewed = true)))
        assertEquals(listOf(listOf("r", "seen") to true, listOf("seen") to false), repo.marks)
    }

    @Test
    fun unauthorizedDoesNotCommitMutationAndNotifiesRenewal() = runBlocking {
        var renewed = 0
        val repo = FakeEventsRepository().apply { markFailure = FrigateHttpException(401, "Expired") }
        val actions = EventsActions(repo, { renewed++ })
        assertThrows(FrigateHttpException::class.java) { runBlocking { actions.mark(listOf(eventReview("r"))) } }
        assertEquals(1, renewed)
        assertTrue(repo.marks.isEmpty())
    }

    @Test
    fun singleQueuedExportAndPartialBatchRemainDistinguishable() = runBlocking {
        val actions = EventsActions(FakeEventsRepository(), {})
        assertEquals("queued", actions.export(listOf(eventReview("r")), 200.0, null).succeeded["r"]?.status)
        assertTrue(actions.export(emptyList(), 200.0, null).succeeded.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { actions.export((0..50).map { eventReview("$it") }, 200.0, null) } }
    }

    @Test
    fun repeatedSubmitWhilePendingMakesOnlyOneMutation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repo = object : EventsRepository by FakeEventsRepository() {
            var requests = 0
            override suspend fun markViewed(ids: List<String>, reviewed: Boolean) { requests++; gate.await() }
        }
        val actions = EventsActions(repo, {})
        val first = launch(start = CoroutineStart.UNDISPATCHED) { actions.mark(listOf(eventReview("r"))) }
        assertThrows(EventsBusyException::class.java) { runBlocking { actions.mark(listOf(eventReview("r"))) } }
        assertEquals(1, repo.requests)
        gate.complete(Unit)
        first.join()
    }
}
