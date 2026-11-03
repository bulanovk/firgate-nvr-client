package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MotionRegionModelTest {
    @Test
    fun catalogRetainsCameraSourceAspectsAndFallsBackOnlyForUnknownDimensions() {
        val catalog = eventsCatalog(listOf(
            com.frigate.android.core.server.FrigateCamera("yard", "Yard", true, detectWidth = 640, detectHeight = 480),
            com.frigate.android.core.server.FrigateCamera("unknown", "Unknown", true),
        ), "admin")
        assertEquals(4.0 / 3, catalog.cameraAspects.getValue("yard"), .0001)
        assertEquals(16.0 / 9, catalog.cameraAspects.getValue("unknown"), .0001)
        assertEquals("Yard", catalog.cameras["yard"])
        assertEquals("admin", catalog.role)
    }

    @Test
    fun missingHeatmapContributesToDenominator() {
        val records = listOf(MotionRecording(0.0, 10.0, mapOf(0 to 200)), MotionRecording(10.0, 20.0, emptyMap()))
        assertEquals(mapOf(0 to 100.0), mergedHeatmap(records, EventsRange(0.0, 20.0, null)))
        assertTrue(mergedHeatmap(records, EventsRange(20.0, 30.0, null)).isEmpty())
        assertTrue(matchesRegion(mapOf(0 to 1.0), setOf(0, 3)))
        assertFalse(matchesRegion(emptyMap(), setOf(0)))
        assertTrue(matchesRegion(emptyMap(), emptySet()))
    }

    @Test
    fun regionPendingApplyCancelAndCameraChange() {
        val selection = MotionRegionSelection("yard")
        selection.toggle(0)
        selection.toggle(17)
        assertTrue(selection.applied.isEmpty())
        assertEquals(NormalizedRect(0.0, 0.0, .125, .125), regionBounds(selection.pending))
        selection.apply()
        selection.toggle(255)
        selection.cancel()
        assertEquals(setOf(0, 17), selection.pending)
        selection.changeCamera("street")
        assertTrue(selection.applied.isEmpty())
        assertTrue(selection.pending.isEmpty())
        assertTrue(selection.crop)
        assertEquals(82, selection.dimPercent)
        assertEquals(.06, motionDimAlpha(255.0, 255.0, 82), .00001)
        assertEquals(.82, motionDimAlpha(0.0, 255.0, 82), .00001)
        assertEquals(.75, regionAspect(NormalizedRect(0.0, 0.0, .01, 1.0), 16.0 / 9))
        assertEquals(4.0, regionAspect(NormalizedRect(0.0, 0.0, 1.0, .01), 16.0 / 9))
    }
}

class EventsTimelineModelTest {
    @Test
    fun mappingClampsAndHitTestingFindsActualReview() {
        val model = EventsTimelineModel(EventsRange(100.0, 200.0, null), listOf(eventReview("r")), emptyList())
        assertEquals(100.0, model.timeAt(-1.0))
        assertEquals(200.0, model.timeAt(2.0))
        assertEquals(.5, model.fractionAt(150.0))
        assertEquals("r", model.reviewAt(125.0))
        assertNull(model.reviewAt(135.0))
    }

    @Test
    fun dayLabelsFitAvailableHeightAndZoomRevealsQuarterHours() {
        val range = EventsRange(0.0, 86400.0, null)
        val compact = timelineTicks(range, "UTC", maxTicks = 19)
        assertTrue(compact.size in 2..19)
        assertTrue(compact.zipWithNext().all { (a, b) -> (b.timestamp - a.timestamp) / 86400 * 600 >= 32 })
        assertTrue(compact.all { it.timestamp % 900 == 0.0 })
        val zoomed = timelineTicks(range, "UTC", maxTicks = 151)
        assertEquals(97, zoomed.size)
        assertEquals(900.0, zoomed[1].timestamp - zoomed[0].timestamp)
    }

    @Test
    fun quarterHourTicksStayClockAlignedAndRepeatedDstLabelsKeepDistinctTimes() {
        val start = java.time.Instant.parse("2026-10-25T00:00:00Z").epochSecond.toDouble()
        val ticks = timelineTicks(EventsRange(start + 1, start + 7200, null), "Europe/Berlin")
        assertTrue(ticks.all { it.timestamp % 900 == 0.0 })
        assertEquals(ticks.size, ticks.map { it.timestamp }.distinct().size)
        assertTrue(ticks.map { it.label }.size > ticks.map { it.label }.distinct().size)
    }
}
