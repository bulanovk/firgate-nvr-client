package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MseLiveBufferPolicyTest {
    @Test
    fun buffersAheadOfLiveMseFragmentsToAvoidFrequentRebuffering() {
        assertEquals(5_000L, MseLiveBufferPolicy.minimumBufferMs)
        assertEquals(15_000L, MseLiveBufferPolicy.maximumBufferMs)
        assertEquals(250L, MseLiveBufferPolicy.bufferForPlaybackMs)
        assertEquals(1_000L, MseLiveBufferPolicy.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun capsRetainedMediaAtFifteenSeconds() {
        assertEquals(15_000L, MseLiveBufferPolicy.retainedWindowMs)
    }

    @Test
    fun marksLagOverTenSecondsStalled() {
        assertTrue(MseLiveBufferPolicy.isStalled(10_001L))
        assertTrue(!MseLiveBufferPolicy.isStalled(10_000L))
    }

    @Test
    fun avoidsSpeedingUpNearTheLiveEdge() {
        assertEquals(1.0f, MseLiveBufferPolicy.playbackRateForLag(2_000L))
        assertEquals(1.125f, MseLiveBufferPolicy.playbackRateForLag(3_000L))
    }

    @Test
    fun capsCatchUpRateAtTwoX() {
        assertEquals(1.0f, MseLiveBufferPolicy.playbackRateForLag(0L))
        assertTrue(MseLiveBufferPolicy.playbackRateForLag(20_000L) <= 2.0f)
        assertEquals(2.0f, MseLiveBufferPolicy.playbackRateForLag(20_000L))
    }
}
