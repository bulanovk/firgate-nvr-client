package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ReviewRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewControllerTest {
    private class FakeClock(var now: Long = 1_700_000_000_000L) {
        fun advance(millis: Long) {
            now += millis
        }

        fun seconds(): Double = now / 1000.0
    }

    /**
     * The delays of the controller.
     *
     * A delay of the refresh loop moves the clock of the test forward, so the deadline of the
     * scheduler expires. The delay of the reconcile loop is parked instead: it has to wait for the
     * test and it must not move the clock.
     */
    private class FakeSleep(private val clock: FakeClock) {
        private val parked = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun sleep(millis: Long) {
            if (millis >= REVIEW_RECONCILE_MILLIS) {
                val gate = CompletableDeferred<Unit>()
                parked += gate
                gate.await()
                return
            }
            clock.advance(millis)
        }

        /** Lets the parked delay of the reconcile loop expire. */
        fun expire() {
            val gates = parked.toList()
            parked.clear()
            gates.forEach { it.complete(Unit) }
        }
    }

    private class FakeRepository : ReviewRepository {
        val loadCalls = mutableListOf<List<String>>()
        val markedIds = mutableListOf<List<String>>()
        var loadResult: List<FrigateReview> = emptyList()
        var loadFailure: Throwable? = null
        var markFailure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun loadReviews(
            cameraIds: List<String>,
            severity: String?,
            reviewed: Int?,
            limit: Int?,
            before: Double?,
            after: Double?,
        ): List<FrigateReview> {
            loadCalls += cameraIds
            gate?.await()
            loadFailure?.let { throw it }
            return loadResult
        }

        override suspend fun markReviewsViewed(ids: List<String>, reviewed: Boolean) {
            markedIds += ids
            markFailure?.let { throw it }
        }
    }

    @Test
    fun loadsUnreviewedAlertsForTheActiveSession() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        val controller = controller(repository, clock)

        controller.setActive(true, setOf("cam"))

        assertEquals(listOf("cam"), repository.loadCalls.single())
        assertEquals(listOf("r1"), controller.state.value.items.map { it.id })
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun keepsItemsWhenARefreshFails() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        val controller = controller(repository, clock)
        controller.setActive(true, setOf("cam"))

        repository.loadFailure = FrigateHttpException(500, "boom")
        controller.invalidate(0)

        assertEquals(listOf("r1"), controller.state.value.items.map { it.id })
        assertTrue(controller.state.value.refreshFailed)
    }

    @Test
    fun markViewedRemovesTheCardOnlyAfterSuccess() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        val controller = controller(repository, clock)
        controller.setActive(true, setOf("cam"))

        controller.markViewed("r1")

        assertEquals(listOf(listOf("r1")), repository.markedIds)
        assertTrue(controller.state.value.items.isEmpty())
        assertTrue(controller.state.value.markFailedIds.isEmpty())
    }

    @Test
    fun failedMarkKeepsTheCardAndRecordsTheFailure() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        val controller = controller(repository, clock)
        controller.setActive(true, setOf("cam"))

        repository.markFailure = FrigateHttpException(500, "boom")
        controller.markViewed("r1")

        assertEquals(listOf("r1"), controller.state.value.items.map { it.id })
        assertEquals(setOf("r1"), controller.state.value.markFailedIds)
    }

    @Test
    fun aLateLoadDoesNotRestoreAMarkedCard() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        val controller = controller(repository, clock)
        controller.setActive(true, setOf("cam"))

        repository.gate = CompletableDeferred()
        controller.invalidate(0)
        controller.markViewed("r1")
        repository.loadResult = listOf(review("r1", clock.seconds() - 30.0))
        repository.gate?.complete(Unit)

        assertTrue(controller.state.value.items.isEmpty())
    }

    @Test
    fun invalidationsDoNotOverlapAndKeepTheEarliestDeadline() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.gate = CompletableDeferred()
        val controller = controller(repository, clock)
        val startedAt = clock.now

        controller.setActive(true, setOf("cam"))
        assertEquals(1, repository.loadCalls.size)

        controller.invalidate(1_000L)
        controller.invalidate(6_000L)
        repository.gate?.complete(Unit)

        assertEquals(2, repository.loadCalls.size)
        assertEquals(1_000L, clock.now - startedAt)
    }

    @Test
    fun stopsWorkingWhenTheSessionBecomesInactive() {
        val clock = FakeClock()
        val repository = FakeRepository()
        val controller = controller(repository, clock)
        controller.setActive(true, setOf("cam"))

        controller.setActive(false, setOf("cam"))
        controller.invalidate(0)

        assertEquals(1, repository.loadCalls.size)
    }

    @Test
    fun unauthorizedResponsesReachTheSessionOwner() {
        val clock = FakeClock()
        val repository = FakeRepository()
        repository.loadFailure = FrigateHttpException(401, "unauthorized")
        var unauthorized = 0
        val controller = controller(repository, clock, onUnauthorized = { unauthorized++ })

        controller.setActive(true, setOf("cam"))

        assertEquals(1, unauthorized)
    }

    @Test
    fun theCarouselIsReconciledWithTheServerEveryThirtySeconds() {
        val clock = FakeClock()
        val repository = FakeRepository()
        val sleep = FakeSleep(clock)
        val controller = controller(repository, clock, sleep)

        controller.setActive(true, setOf("cam"))
        assertEquals(1, repository.loadCalls.size)

        sleep.expire()

        assertEquals(2, repository.loadCalls.size)
    }

    @Test
    fun theReconciliationStopsWithTheSession() {
        val clock = FakeClock()
        val repository = FakeRepository()
        val sleep = FakeSleep(clock)
        val controller = controller(repository, clock, sleep)
        controller.setActive(true, setOf("cam"))

        controller.setActive(false, setOf("cam"))
        sleep.expire()

        assertEquals(1, repository.loadCalls.size)
    }

    private fun controller(
        repository: FakeRepository,
        clock: FakeClock,
        sleep: FakeSleep = FakeSleep(clock),
        onUnauthorized: () -> Unit = {},
    ) = ReviewController(
        repository = repository,
        scope = CoroutineScope(Dispatchers.Unconfined),
        nowSeconds = { clock.seconds() },
        nowMillis = { clock.now },
        sleep = sleep::sleep,
        onUnauthorized = onUnauthorized,
    )

    private fun review(id: String, start: Double) = FrigateReview(
        id = id,
        camera = "cam",
        startTime = start,
        endTime = start + 5.0,
        severity = "alert",
        hasBeenReviewed = false,
        thumbnailPath = null,
        objectLabels = listOf("person"),
    )
}
