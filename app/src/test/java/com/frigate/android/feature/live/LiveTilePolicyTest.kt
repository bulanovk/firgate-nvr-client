package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveTilePolicyTest {
    @Test
    fun recordOffDoesNotGateVisibleActivity() {
        val activity = CameraActivity(activeMotion = true, recordingEnabled = false)
        assertTrue(liveTileDemand(true, true, activity, "stream"))
        assertFalse(liveTileDemand(false, true, activity, "stream"))
        assertFalse(liveTileDemand(true, false, activity, "stream"))
        assertFalse(liveTileDemand(true, true, activity.copy(offline = true), "stream"))
        assertFalse(liveTileDemand(true, true, activity, ""))
    }

    @Test
    fun firstFrameBelongsToCurrentOutputAndDetachResetsIt() {
        val readiness = LiveOutputReadiness()
        val oldOutput = Any()
        val current = Any()
        readiness.attach(oldOutput)
        assertFalse(readiness.ready)
        readiness.attach(current)
        readiness.rendered(oldOutput)
        assertFalse(readiness.ready)
        readiness.rendered(current)
        assertTrue(readiness.ready)
        readiness.detach(oldOutput)
        assertTrue(readiness.ready)
        readiness.detach(current)
        readiness.rendered(current)
        assertFalse(readiness.ready)
    }

    @Test
    fun endActivityHasFiveHundredMillisecondGraceButHiddenStopsImmediately() {
        val live = LiveTilePlayback().update(true, true, 0)
        val returning = live.update(false, true, 1000)
        assertTrue(returning.update(false, true, 1499).keepPlayer)
        assertFalse(returning.update(false, true, 1500).keepPlayer)
        assertTrue(returning.update(true, true, 1499).update(false, true, 1600).keepPlayer)
        assertFalse(live.update(false, false, 1).keepPlayer)
    }

    @Test
    fun hiddenCameraLoadsOneStillBeforeItIsEverVisible() {
        // Not visible and never attempted: one load, so the still is ready when the user scrolls to it.
        assertEquals(0L, liveSnapshotInterval(false, true, false, false, false, attempted = false))
        // Once attempted, a hidden camera stops polling again.
        assertEquals(-1L, liveSnapshotInterval(false, true, false, false, false, attempted = true))
        // Disabled or offline cameras never load a still, visible or not.
        assertEquals(-1L, liveSnapshotInterval(false, false, false, false, false, attempted = false))
        assertEquals(-1L, liveSnapshotInterval(false, true, true, false, false, attempted = false))
    }

    @Test
    fun pollingTracksStartupReadyGraceAndErrorBackoff() {
        assertEquals(30000L, liveSnapshotInterval(true, true, false, false, false))
        assertEquals(200L, liveSnapshotInterval(true, true, false, true, false))
        assertEquals(60000L, liveSnapshotInterval(true, true, false, true, true))
        assertEquals(300L, liveSnapshotInterval(true, true, false, false, true))
        assertEquals(-1L, liveSnapshotInterval(false, true, false, true, true))
        assertEquals(-1L, liveSnapshotInterval(true, false, false, false, false))
        assertEquals(-1L, liveSnapshotInterval(true, true, true, true, false))
        assertEquals(200L, snapshotDelayAfterLoad(200, 100, false))
        assertEquals(1L, snapshotDelayAfterLoad(200, 201, false))
        assertEquals(1000L, snapshotDelayAfterLoad(200, 10, true))
    }

    @Test
    fun geometryPreservesPortraitWideAndDefaultBuckets() {
        assertEquals(16f / 9, liveTileAspect(1920, 1080))
        assertEquals(32f / 9, liveTileAspect(3840, 1080))
        assertEquals(8f / 9, liveTileAspect(720, 1280))
        assertEquals(16f / 9, liveTileAspect(0, 0))
    }
}
