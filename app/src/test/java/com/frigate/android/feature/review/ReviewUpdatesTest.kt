package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReviewUpdatesTest {
    @Test
    fun parsesReviewTopicWithNestedPayload() {
        val update = ReviewUpdates.parseUpdate(frame("end", "alert", objectsBefore = 1, objectsAfter = 2))

        assertEquals("end", update?.type)
        assertEquals("alert", update?.afterSeverity)
        assertEquals(1, update?.beforeObjectCount)
        assertEquals(2, update?.afterObjectCount)
        assertEquals(true, update?.objectsGrew)
    }

    @Test
    fun ignoresOtherTopicsAndMalformedFrames() {
        assertNull(ReviewUpdates.parseUpdate("""{"topic":"stats","payload":"{}"}"""))
        assertNull(ReviewUpdates.parseUpdate("""{"topic":"reviews"}"""))
        assertNull(ReviewUpdates.parseUpdate("not json"))
        assertNull(ReviewUpdates.parseUpdate("""{"topic":"reviews","payload":"not json"}"""))
    }

    @Test
    fun schedulesEndAfterOneSecondAndNewAfterSix() {
        assertEquals(1_000L, ReviewUpdates.refreshDelayMillis(frame("end", "alert")))
        assertEquals(6_000L, ReviewUpdates.refreshDelayMillis(frame("new", "alert")))
        assertEquals(6_000L, ReviewUpdates.refreshDelayMillis(frame("genai", "alert")))
    }

    @Test
    fun schedulesObjectGrowthAfterFiveSeconds() {
        assertEquals(5_000L, ReviewUpdates.refreshDelayMillis(frame("update", "alert", objectsBefore = 1, objectsAfter = 3)))
        assertNull(ReviewUpdates.refreshDelayMillis(frame("update", "alert", objectsBefore = 3, objectsAfter = 3)))
    }

    @Test
    fun ignoresUpdatesThatAreNotAlerts() {
        assertNull(ReviewUpdates.refreshDelayMillis(frame("end", "detection")))
        assertNull(ReviewUpdates.refreshDelayMillis(frame("new", "significant_motion")))
    }

    @Test
    fun keepsOnlyUnreviewedAlertsStartedWithinTheLastHour() {
        val now = 1_000_000.0
        val items = listOf(
            review("fresh", camera = "cam", start = now - 10.0, severity = "alert", reviewed = false),
            review("boundary", camera = "cam", start = now - 3600.0, severity = "alert", reviewed = false),
            review("old", camera = "cam", start = now - 7200.0, severity = "alert", reviewed = false),
            review("reviewed", camera = "cam", start = now - 20.0, severity = "alert", reviewed = true),
            review("detection", camera = "cam", start = now - 20.0, severity = "detection", reviewed = false),
            review("other-camera", camera = "other", start = now - 20.0, severity = "alert", reviewed = false),
        )

        val alerts = unreviewedAlerts(items, setOf("cam"), now)

        assertEquals(listOf("fresh"), alerts.map { it.id })
    }

    @Test
    fun sortsNewestFirstRemovesDuplicatesAndAppliesTheLimit() {
        val now = 1_000_000.0
        val items = buildList {
            repeat(12) { index ->
                add(review("r$index", camera = "cam", start = now - index * 10.0, severity = "alert", reviewed = false))
            }
            add(review("r0", camera = "cam", start = now - 5.0, severity = "alert", reviewed = false))
        }

        val alerts = unreviewedAlerts(items, setOf("cam"), now)

        assertEquals(10, alerts.size)
        assertEquals("r0", alerts.first().id)
        assertEquals(listOf("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9"), alerts.map { it.id })
    }

    private fun review(
        id: String,
        camera: String,
        start: Double,
        severity: String = "alert",
        reviewed: Boolean = false,
    ) = FrigateReview(
        id = id,
        camera = camera,
        startTime = start,
        endTime = start + 5.0,
        severity = severity,
        hasBeenReviewed = reviewed,
        thumbnailPath = null,
        objectLabels = listOf("person"),
    )

    private fun frame(type: String, severity: String, objectsBefore: Int = 0, objectsAfter: Int = 0): String {
        val payload = """
            {"type":"$type",
             "before":{"severity":"$severity","data":{"objects":[${objects( objectsBefore)}]}},
             "after":{"severity":"$severity","data":{"objects":[${objects(objectsAfter)}]}}}
        """.trimIndent()
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")
        return """{"topic":"reviews","payload":"$escaped"}"""
    }

    private fun objects(count: Int): String = (0 until count).joinToString(",") { """{"label":"person"}""" }
}
