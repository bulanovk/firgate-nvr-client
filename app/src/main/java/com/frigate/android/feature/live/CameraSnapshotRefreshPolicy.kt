package com.frigate.android.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun pollCameraSnapshots(
    intervals: Flow<Long>,
    nextDelayMs: (Long) -> Long,
    refresh: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        intervals.mapLatest { interval ->
            if (interval < 0) awaitCancellation()
            delay(nextDelayMs(interval))
        }.first()
        refresh()
    }
}

internal object CameraSnapshotRefreshPolicy {
    const val DEFAULT_INTERVAL_MS = 30_000L
}

internal fun snapshotRefreshDelayMs(
    lastRefreshAtMs: Long?,
    nowMs: Long,
    intervalMs: Long,
): Long = lastRefreshAtMs?.let { lastRefreshAt ->
    (intervalMs - (nowMs - lastRefreshAt)).coerceIn(0L, intervalMs)
} ?: 0L

internal enum class CameraSnapshotPresentation {
    LOADING,
    UNAVAILABLE,
    FRESH,
    STALE,
}

internal fun cameraSnapshotPresentation(
    hasSnapshot: Boolean,
    refreshFailed: Boolean,
): CameraSnapshotPresentation = when {
    hasSnapshot && refreshFailed -> CameraSnapshotPresentation.STALE
    hasSnapshot -> CameraSnapshotPresentation.FRESH
    refreshFailed -> CameraSnapshotPresentation.UNAVAILABLE
    else -> CameraSnapshotPresentation.LOADING
}
