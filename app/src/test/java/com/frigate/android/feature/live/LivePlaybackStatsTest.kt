package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LivePlaybackStatsTest {
    @Test
    fun statisticsShowActualFormatsAndBufferWithoutInventingAnAudioTrack() {
        val stats = LivePlaybackStats(
            width = 3840, height = 2160, videoCodec = "video/hevc",
            bufferedMs = 1234, videoFrames = 42,
        )
        assertEquals("MSE · 3840×2160 · video/hevc\nBuffer 1.2 s · Video frames 42\nAudio: none", stats.summary())
    }

    @Test
    fun audioSummarySeparatesDecodedOutputFromMuteState() {
        val stats = LivePlaybackStats(audioCodec = "audio/mp4a-latm", audioBuffers = 18, muted = true)
        assertEquals("Audio: audio/mp4a-latm · muted · Output buffers 18", stats.audioSummary())
        assertEquals("Audio: audio/mp4a-latm · on · Output buffers 18", stats.copy(muted = false).audioSummary())
        assertFalse(stats.audioSummary().contains("playing"))
    }
}
