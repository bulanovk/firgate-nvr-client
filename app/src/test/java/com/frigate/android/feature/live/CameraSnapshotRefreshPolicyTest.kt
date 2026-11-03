package com.frigate.android.feature.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CameraSnapshotRefreshPolicyTest {
    @Test
    fun modeChangeDoesNotCancelInFlightSnapshot(): Unit = runBlocking {
        withTimeout(3000) {
            val intervals = MutableStateFlow(30_000L)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            var cancelled = false
            var requests = 0
            val job = launch {
                pollCameraSnapshots(intervals, nextDelayMs = { if (requests == 0) 0 else it }) {
                    requests++
                    started.complete(Unit)
                    try {
                        finish.await()
                        completed.complete(Unit)
                    } catch (error: CancellationException) {
                        cancelled = true
                        throw error
                    }
                }
            }
            try {
                started.await()
                intervals.value = 200
                repeat(5) { yield() }
                assertFalse(cancelled, "Motion must change the next delay, not cancel the image download")
                assertEquals(1, requests)
                finish.complete(Unit)
                completed.await()
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun usesFrigatePwaDefaultStillRefreshInterval() {
        assertEquals(30_000L, CameraSnapshotRefreshPolicy.DEFAULT_INTERVAL_MS)
    }

    @ParameterizedTest
    @CsvSource(
        "-1, 1000, 0",
        "1000, 9000, 22000",
        "1000, 31000, 0",
    )
    fun avoidsRefreshingAgainUntilTheSnapshotIntervalExpires(
        lastRefreshAtMs: Long,
        nowMs: Long,
        expectedDelayMs: Long,
    ) {
        assertEquals(
            expectedDelayMs,
            snapshotRefreshDelayMs(lastRefreshAtMs.takeIf { it >= 0 }, nowMs, 30_000L),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "false, false, LOADING",
        "false, true, UNAVAILABLE",
        "true, false, FRESH",
        "true, true, STALE",
    )
    fun exposesWhetherTheLastSnapshotIsFreshOrStale(
        hasSnapshot: Boolean,
        refreshFailed: Boolean,
        expected: String,
    ) {
        assertEquals(
            expected,
            cameraSnapshotPresentation(hasSnapshot, refreshFailed).name,
        )
    }
}
