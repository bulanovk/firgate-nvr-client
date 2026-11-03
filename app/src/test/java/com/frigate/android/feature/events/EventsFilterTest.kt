package com.frigate.android.feature.events

import com.frigate.android.core.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsFilterTest {
    @Test
    fun countsUseSummaryNotLoadedCards() {
        val summary = FrigateReviewSummary(FrigateReviewCounts(12, 3, 5, 2), mapOf("2026-11-01" to FrigateReviewCounts(7, 2, 3, 1)))
        assertEquals(9 to 3, reviewCounts(summary, null, false))
        assertEquals(12 to 5, reviewCounts(summary, null, true))
        assertEquals(5 to 2, reviewCounts(summary, "2026-11-01", false))
        assertEquals(0 to 0, reviewCounts(summary, "2026-10-01", false))
    }

    @Test
    fun dayUsesTimezoneAndDstNotFixedTwentyFourHours() {
        val spring = eventsRange("2026-03-29", "Europe/Berlin", 0.0)
        val autumn = eventsRange("2026-10-25", "Europe/Berlin", 0.0)
        assertEquals(23 * 3600.0, spring.before - spring.after)
        assertEquals(25 * 3600.0, autumn.before - autumn.after)
        val rolling = eventsRange(null, "Europe/Berlin", 200000.0)
        assertEquals(113600.0, rolling.after)
        assertEquals(200000.0, rolling.before)
        assertThrows(IllegalArgumentException::class.java) { EventsRange(20.0, 10.0, null) }
    }

    @Test
    fun localSeverityAndReviewedSwitchDoNotInventMotionCards() {
        val reviews = listOf(eventReview("a"), eventReview("seen").copy(hasBeenReviewed = true), eventReview("d").copy(severity = "detection"), eventReview("m").copy(severity = "significant_motion"))
        assertEquals(listOf("a"), filteredReviews(reviews, EventsFilter()).map { it.id })
        assertEquals(listOf("a", "seen"), filteredReviews(reviews, EventsFilter(showReviewed = true)).map { it.id })
        assertEquals(setOf("a", "d"), filteredReviews(reviews, EventsFilter(includeOtherSeverity = true)).map { it.id }.toSet())
        assertTrue(filteredReviews(reviews, EventsFilter(type = EventsType.MOTION)).isEmpty())
    }
}

internal fun eventReview(id: String, camera: String = "yard") =
    FrigateReview(id, camera, 120.0, 130.0, "alert", false, null, listOf("person"))
