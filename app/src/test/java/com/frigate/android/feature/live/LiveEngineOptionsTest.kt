package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The PWA offers MSE and WebRTC in the streaming technology select. An engine that cannot be
 * used stays in the list, dimmed, with the reason: it is not removed silently.
 *
 * JSMpeg is not an engine: in the PWA it is the force low-bandwidth switch.
 */
class LiveEngineOptionsTest {
    @Test
    fun theListHoldsTheTwoEnginesOfThePwa() {
        assertEquals(listOf("MSE", "WebRTC"), liveEngineOptions().map { it.label })
    }

    @Test
    fun msegIsTheEngineThatWorks() {
        val mse = liveEngineOptions().first { it.label == "MSE" }
        assertTrue(mse.available)
        assertEquals("mse", mse.value)
    }

    @Test
    fun webRtcStaysInTheListWithTheReasonWhyItCannotBeUsed() {
        val webrtc = liveEngineOptions().first { it.label == "WebRTC" }
        assertFalse(webrtc.available)
        assertEquals("webrtc", webrtc.value)
        assertTrue(webrtc.reason.orEmpty().isNotBlank(), "the reason has to be told to the user")
    }

    @Test
    fun jmpegIsNotAnEngine() {
        assertTrue(liveEngineOptions().none { it.label.equals("MJPEG", ignoreCase = true) })
    }
}
