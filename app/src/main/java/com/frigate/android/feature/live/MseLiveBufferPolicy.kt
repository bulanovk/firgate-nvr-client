package com.frigate.android.feature.live

import kotlin.math.min

object MseLiveBufferPolicy {
    const val retainedWindowMs = 15_000L
    const val minimumBufferMs = 5_000L
    const val maximumBufferMs = retainedWindowMs
    const val bufferForPlaybackMs = 250L
    const val bufferForPlaybackAfterRebufferMs = 1_000L
    const val stalledLagMs = 10_000L
    const val catchUpThresholdMs = 2_000L
    const val maximumPlaybackRate = 2.0f

    fun isStalled(lagMs: Long): Boolean = lagMs > stalledLagMs

    fun playbackRateForLag(lagMs: Long): Float {
        val catchUpLagMs = (lagMs - catchUpThresholdMs).coerceAtLeast(0L)
        val catchUpRangeMs = stalledLagMs - catchUpThresholdMs
        return 1.0f + min(catchUpLagMs, catchUpRangeMs).toFloat() / catchUpRangeMs
    }
}
