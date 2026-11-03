package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MsePlaybackHtmlTest {
    @Test
    fun keepsSnapshotUntilTheFirstDecodedFrameIsReady() {
        val script = mseFirstFrameRevealScript()

        val waitingHandler = script.substringAfter("video.onwaiting").substringBefore("video.onerror")
        assertTrue(waitingHandler.contains("status('Buffering')"))
        assertTrue(!waitingHandler.contains("keepSnapshotVisible"))
    }

    @Test
    fun waitsForStableBufferedPlaybackBeforeRevealingVideo() {
        val script = mseFirstFrameRevealScript()

        assertTrue(script.contains("video.buffered.length"))
        assertTrue(script.contains("video.readyState"))
        assertTrue(script.contains("startupRevealTimer"))
        assertTrue(!script.contains("setTimeout(reveal, 0)"))
    }

    @Test
    fun boundsMseBufferAndKeepsSegmentMode() {
        val script = mseBufferMaintenanceScript()

        assertTrue(script.contains("sourceBuffer.mode = 'segments'"))
        assertTrue(script.contains("sourceBuffer.remove"))
        assertTrue(script.contains("sourceBuffer.buffered"))
    }

    @Test
    fun offersHighProfileH264CodecUsedByHighResolutionCameras() {
        assertTrue(mseSupportedCodecsScript().contains("avc1.640033"))
    }

    @Test
    fun checksStableBufferWhenMseDeliversData() {
        val script = mseBufferMaintenanceScript()

        assertTrue(script.contains("revealVideoAfterStableBuffer()"))
    }
}
