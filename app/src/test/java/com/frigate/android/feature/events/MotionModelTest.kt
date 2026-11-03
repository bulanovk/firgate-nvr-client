package com.frigate.android.feature.events

import com.frigate.android.core.server.MotionPoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MotionModelTest {
    @Test
    fun motionOnlyExcludesObjectOverlapAndOngoingEndsNow() {
        val points = (0..5).map { MotionPoint(it * 15.0, 1.0, "yard,street") }
        val reviews = listOf(eventReview("a").copy(startTime = 15.0, endTime = 30.0),
            eventReview("b").copy(startTime = 45.0, endTime = null, severity = "significant_motion", objectLabels = emptyList(), detections = listOf("obj")))
        assertEquals(listOf(EventsRange(0.0, 15.0, null), EventsRange(30.0, 45.0, null)), motionOnlyRanges(points, reviews, "yard", 90.0))
        assertEquals(listOf(EventsRange(0.0, 90.0, null)), motionOnlyRanges(points, reviews, "street", 90.0))
    }

    @Test
    fun duplicateBucketsUseMaxAndOnlyContiguousBucketsMerge() {
        val points = listOf(MotionPoint(100.0, 0.0, "yard"), MotionPoint(100.0, 3.0, "yard"),
            MotionPoint(115.0, 1.0, "yard"), MotionPoint(120.0, 1.0, "yard"), MotionPoint(160.0, -1.0, "yard"))
        assertEquals(listOf(EventsRange(100.0, 130.0, null), EventsRange(120.0, 135.0, null)), motionOnlyRanges(points, emptyList(), "yard", 200.0))
        assertTrue(motionOnlyRanges(points, emptyList(), "unknown", 200.0).isEmpty())
    }

    @Test
    fun gridSkipsThirtySecondObjectSegmentsButNormalTickIsHalfSecond() {
        val query = EventsQuery(listOf("yard"), EventsRange(0.0, 120.0, null), "UTC")
        val points = listOf(MotionPoint(0.0, 1.0, "yard"), MotionPoint(30.0, 1.0, "yard"), MotionPoint(90.0, 1.0, "yard"))
        val review = eventReview("obj").copy(startTime = 35.0, endTime = 40.0)
        assertEquals(.5, nextMotionTimestamp(0.0, query, points, listOf(review), false, 120.0))
        assertEquals(90.0, nextMotionTimestamp(30.0, query, points, listOf(review), true, 120.0))
        assertNull(nextMotionTimestamp(119.9, query, points, emptyList(), false, 120.0))
        assertEquals(listOf(4, 8, 12, 16), MOTION_SPEEDS)
        assertEquals(8, MOTION_DEFAULT_SPEED)
    }
}
