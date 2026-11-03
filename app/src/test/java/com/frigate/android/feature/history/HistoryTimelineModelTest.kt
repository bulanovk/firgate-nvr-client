package com.frigate.android.feature.history

import com.frigate.android.core.server.CoverageSpan
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.UnavailableSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryTimelineModelTest {
    private val camera = "front_door"

    private fun review(
        id: String = "r1",
        camera: String = "front_door",
        startTime: Double = 1_500.0,
        endTime: Double? = 1_520.0,
        severity: String = "alert",
        reviewed: Boolean = false,
        labels: List<String> = listOf("person"),
    ) = FrigateReview(
        id = id,
        camera = camera,
        startTime = startTime,
        endTime = endTime,
        severity = severity,
        hasBeenReviewed = reviewed,
        thumbnailPath = null,
        objectLabels = labels,
    )

    private fun build(
        reviews: List<FrigateReview> = emptyList(),
        motion: List<MotionPoint> = emptyList(),
        unavailable: List<UnavailableSpan> = emptyList(),
        coverage: List<CoverageSpan> = emptyList(),
        range: HistoryRange = HistoryRange(after = 1_000.0, before = 2_000.0),
        segmentDuration: Double = 30.0,
    ) = HistoryTimelineModel.build(
        camera = camera,
        reviews = reviews,
        motion = motion,
        unavailable = unavailable,
        coverage = coverage,
        range = range,
        segmentDuration = segmentDuration,
    )

    @Test
    fun segmentsAreOrderedNewestFirstAndCoverTheRangeWithHeadroom() {
        val segments = build()

        assertTrue(segments.isNotEmpty())
        assertEquals(
            segments.sortedByDescending { it.startTime }.map { it.startTime },
            segments.map { it.startTime },
        )
        assertEquals(2_040.0, segments.first().startTime, 0.001)
        assertEquals(930.0, segments.last().startTime, 0.001)
    }

    @Test
    fun alertSeverityWinsOverDetectionInTheSameSegment() {
        val segments = build(
            reviews = listOf(
                review(id = "detection", severity = "detection"),
                review(id = "alert", severity = "alert"),
            ),
        )

        val segment = segments.first { it.startTime == 1_500.0 }
        assertEquals("alert", segment.severity)
    }

    @Test
    fun detectionSeverityIsUsedWhenNoAlertCoversTheSegment() {
        val segments = build(reviews = listOf(review(severity = "detection")))

        val segment = segments.first { it.startTime == 1_500.0 }
        assertEquals("detection", segment.severity)
        assertEquals(2, HistoryTimelineModel.severityRank("detection"))
        assertEquals(3, HistoryTimelineModel.severityRank("alert"))
        assertEquals(1, HistoryTimelineModel.severityRank("significant_motion"))
        assertEquals(0, HistoryTimelineModel.severityRank(null))
    }

    @Test
    fun segmentsWithoutReviewsCarryNoSeverity() {
        val segments = build()

        val segment = segments.first { it.startTime == 1_470.0 }
        assertNull(segment.severity)
        assertFalse(segment.reviewed)
        assertEquals(0, segment.objectCount)
    }

    @Test
    fun reviewedSegmentsAreMarked() {
        val segments = build(reviews = listOf(review(reviewed = true)))

        val segment = segments.first { it.startTime == 1_500.0 }
        assertTrue(segment.reviewed)
    }

    @Test
    fun reviewsOfOtherCamerasAreIgnored() {
        val segments = build(reviews = listOf(review(camera = "garage")))

        val segment = segments.first { it.startTime == 1_500.0 }
        assertNull(segment.severity)
    }

    @Test
    fun objectCountCountsDistinctLabelsOfTheCoveringReviews() {
        val segments = build(
            reviews = listOf(
                review(id = "r1", labels = listOf("person", "car")),
                review(id = "r2", labels = listOf("person", "dog")),
            ),
        )

        val segment = segments.first { it.startTime == 1_500.0 }
        assertEquals(3, segment.objectCount)
    }

    @Test
    fun motionIsSplitSymmetricallyAroundTheSegmentAxis() {
        val segments = build(
            motion = listOf(
                MotionPoint(startTime = 90.0, motion = 50.0, camera = camera),
                MotionPoint(startTime = 105.0, motion = 30.0, camera = camera),
            ),
            range = HistoryRange(after = 0.0, before = 200.0),
        )

        val segment = segments.first { it.startTime == 90.0 }
        assertEquals(50.0, segment.firstHalfMotion, 0.001)
        assertEquals(30.0, segment.secondHalfMotion, 0.001)
    }

    @Test
    fun motionOfOtherCamerasIsIgnored() {
        val segments = build(
            motion = listOf(MotionPoint(startTime = 90.0, motion = 50.0, camera = "garage")),
            range = HistoryRange(after = 0.0, before = 200.0),
        )

        val segment = segments.first { it.startTime == 90.0 }
        assertEquals(0.0, segment.firstHalfMotion, 0.001)
        assertEquals(0.0, segment.secondHalfMotion, 0.001)
    }

    @Test
    fun unavailableWindowsMarkTheSegmentsWithoutRecording() {
        val segments = build(
            unavailable = listOf(UnavailableSpan(camera = camera, startTime = 2_000.0, endTime = 2_100.0)),
            range = HistoryRange(after = 1_900.0, before = 2_200.0),
        )

        assertFalse(segments.first { it.startTime == 2_010.0 }.hasRecording)
        assertFalse(segments.first { it.startTime == 2_070.0 }.hasRecording)
        assertTrue(segments.first { it.startTime == 1_980.0 }.hasRecording)
        assertTrue(segments.first { it.startTime == 2_100.0 }.hasRecording)
    }

    @Test
    fun coverageWithoutTheMainStreamMarksSubOnlySegments() {
        val segments = build(
            coverage = listOf(CoverageSpan(startTime = 1_500.0, endTime = 1_560.0, streams = listOf("sub"))),
        )

        assertTrue(segments.first { it.startTime == 1_500.0 }.subOnly)
        assertFalse(segments.first { it.startTime == 1_470.0 }.subOnly)
    }

    @Test
    fun coverageWithTheMainStreamIsNotSubOnly() {
        val segments = build(
            coverage = listOf(CoverageSpan(startTime = 1_500.0, endTime = 1_560.0, streams = listOf("main", "sub"))),
        )

        assertFalse(segments.first { it.startTime == 1_500.0 }.subOnly)
    }

    @Test
    fun theSegmentStepFollowsTheZoomLevel() {
        val coarse = build(segmentDuration = 30.0)
        val medium = build(segmentDuration = 15.0)
        val fine = build(segmentDuration = 5.0)

        assertEquals(30.0, coarse[0].startTime - coarse[1].startTime, 0.001)
        assertEquals(15.0, medium[0].startTime - medium[1].startTime, 0.001)
        assertEquals(5.0, fine[0].startTime - fine[1].startTime, 0.001)
    }

    @Test
    fun theZoomLevelsOfThePwaAreThirtyFifteenAndFiveSeconds() {
        assertEquals(listOf(30.0, 15.0, 5.0), HISTORY_ZOOM_LEVELS.map { it.segmentDuration })
        assertEquals(listOf(15, 5, 1), HISTORY_ZOOM_LEVELS.map { it.timestampSpreadMinutes })
    }

    @Test
    fun theTimelineStaysWhereItIsWhileThePlayheadIsVisible() {
        // The PWA scrolls the timeline only when the playhead leaves the visible range, so the
        // scroll of the user is not taken away while the playback runs.
        assertNull(historyTimelineScrollTarget(playheadIndex = 5, visibleRange = 3..8, halfViewportRows = 4))
    }

    @Test
    fun theTimelineCentresThePlayheadWhenItLeavesTheView() {
        assertEquals(1, historyTimelineScrollTarget(playheadIndex = 5, visibleRange = 0..2, halfViewportRows = 4))
    }

    @Test
    fun theTimelineDoesNotScrollAboveTheFirstRow() {
        assertEquals(0, historyTimelineScrollTarget(playheadIndex = 1, visibleRange = 5..8, halfViewportRows = 4))
    }
}
