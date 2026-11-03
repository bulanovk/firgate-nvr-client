package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveCameraPlayerPoolTest {
    @Test
    fun keepsAStillVisiblePlayerAcrossRecompositionAndReleasesWhenHidden() {
        val pool = LiveCameraPlayerPool<FakePlayer> { it.release() }
        val firstPlayer = pool.getOrCreate("front_door") { FakePlayer() }

        pool.retain(setOf("front_door"))
        val playerAfterScroll = pool.getOrCreate("front_door") {
            error("An active camera should reuse its player")
        }

        assertSame(firstPlayer, playerAfterScroll)
        assertFalse(firstPlayer.released)

        pool.retain(emptySet())
        assertTrue(firstPlayer.released)
    }

    private class FakePlayer {
        var released = false
            private set

        fun release() {
            released = true
        }
    }
}
