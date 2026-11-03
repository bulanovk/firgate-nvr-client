package com.frigate.android.feature.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryScreenModelTest {
    private val bounds = 1_000.0..2_000.0

    @Test
    fun seekKeepsTheWallClockMomentOfTheDay() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.seekTo(1_234.5)

        assertEquals(1_234.5, model.anchorSeconds, 0.001)
    }

    @Test
    fun seekIsClampedToTheRangeOfTheArchive() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.seekTo(500.0)
        assertEquals(1_000.0, model.anchorSeconds, 0.001)

        model.seekTo(5_000.0)
        assertEquals(2_000.0, model.anchorSeconds, 0.001)
    }

    @Test
    fun everySeekTellsThePlayerToMove() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.seekTo(1_400.0)
        model.seekTo(1_300.0)

        assertEquals(2, model.seekToken)
    }

    @Test
    fun theAnchorIsClampedWhenTheRangeChanges() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.bounds = 1_400.0..1_600.0

        assertEquals(1_500.0, model.anchorSeconds, 0.001)
    }

    @Test
    fun zoomStopsAtTheFinestLevel() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.zoomIn()
        model.zoomIn()
        model.zoomIn()

        assertEquals(HISTORY_ZOOM_LEVELS.lastIndex, model.zoomIndex)
        assertEquals(5.0, model.zoom.segmentDuration, 0.001)
        assertEquals(1, model.zoom.timestampSpreadMinutes)
    }

    @Test
    fun zoomStopsAtTheCoarsestLevel() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        model.zoomOut()
        model.zoomOut()

        assertEquals(0, model.zoomIndex)
        assertEquals(30.0, model.zoom.segmentDuration, 0.001)
        assertEquals(15, model.zoom.timestampSpreadMinutes)
    }

    @Test
    fun theFirstZoomLevelIsTheCoarsestOne() {
        val model = HistoryScreenModel(anchorSeconds = 1_500.0, bounds = bounds)

        assertEquals(0, model.zoomIndex)
        assertTrue(model.zoom.segmentDuration > 0.0)
    }
}
