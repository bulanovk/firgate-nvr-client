package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiveAudioDecisionTest {
    @Test
    fun aCameraWithoutDecodedAudioKeepsWaitingUntilTheTimeout() {
        assertEquals(LiveAudioDecision.WAITING, liveAudioDecision(decodedBuffers = 0, waitedMs = 0))
        assertEquals(LiveAudioDecision.WAITING, liveAudioDecision(decodedBuffers = 0, waitedMs = 4_999))
    }

    @Test
    fun decodedAudioEndsTheWaiting() {
        assertEquals(LiveAudioDecision.READY, liveAudioDecision(decodedBuffers = 1, waitedMs = 250))
    }

    @Test
    fun aSilentCameraIsReportedWhenTheTimeoutIsReached() {
        assertEquals(LiveAudioDecision.UNAVAILABLE, liveAudioDecision(decodedBuffers = 0, waitedMs = 5_000))
    }

    @Test
    fun theTimeoutIsShortEnoughToNotTireTheUser() {
        assertEquals(5_000L, LIVE_AUDIO_OUTPUT_TIMEOUT_MS)
    }
}
