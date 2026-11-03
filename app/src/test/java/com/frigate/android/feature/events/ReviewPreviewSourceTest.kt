package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReviewPreviewSourceTest {
    @Test
    fun greatestSameCameraOverlapAndOffsetUseReturnedMetadata() {
        val first = ReviewPreview("yard", 100.0, 120.0, "first.mp4")
        val best = ReviewPreview("yard", 110.0, 200.0, "returned.mp4")
        val range = EventsRange(115.0, 160.0, null)
        assertEquals(best, selectPreview(listOf(first, best, best.copy(cameraId = "other")), "yard", range))
        assertEquals(10.0, previewOffset(best, 120.0))
        assertNull(previewOffset(best, 99.0))
        assertNull(selectPreview(listOf(first), "other", range))
        assertNull(selectPreview(listOf(first), "yard", EventsRange(200.0, 300.0, null)))
    }

    @Test
    fun framesUseNextThreeThenLastFrameOnlyInCurrentHour() {
        val frames = listOf(100.0, 110.0, 120.0, 130.0).map { PreviewFrame("yard", it, "$it.webp") }
        assertEquals(listOf(110.0, 120.0, 130.0), previewFrameCandidates(frames, 105.0).map { it.timestamp })
        assertEquals(listOf(130.0), previewFrameCandidates(frames, 200.0).map { it.timestamp })
        assertTrue(previewFrameCandidates(emptyList(), 100.0).isEmpty())
        assertTrue(usesPreviewFrames(3700.0, 3800.0))
        assertFalse(usesPreviewFrames(3500.0, 3800.0))
    }
}

class ReviewPreviewPolicyTest {
    @Test
    fun stopReleasesEveryPreviewAndReplacingIdentityReleasesPreviousDecoder() {
        val released = mutableListOf<String>()
        val pool = EventsPreviewPool<String>(1, released::add)
        pool.retain(setOf("a"))
        assertEquals("player-a", pool.acquire("a") { "player-a" })
        assertNull(pool.acquire("b") { "player-b" })
        pool.retain(setOf("b"))
        assertEquals(listOf("player-a"), released)
        pool.acquire("b") { "player-b" }
        pool.close()
        pool.close()
        assertEquals(listOf("player-a", "player-b"), released)
    }
}
