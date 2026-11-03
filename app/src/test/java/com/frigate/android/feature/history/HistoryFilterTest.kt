package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryFilterTest {
    private fun review(
        id: String = "review-1",
        camera: String = "front_door",
        severity: String = "alert",
        labels: List<String> = listOf("person"),
        zones: List<String> = listOf("porch"),
    ) = FrigateReview(
        id = id,
        camera = camera,
        startTime = 1_710_000_000.0,
        endTime = 1_710_000_030.0,
        severity = severity,
        hasBeenReviewed = false,
        thumbnailPath = null,
        objectLabels = labels,
        zones = zones,
    )

    @Test
    fun allLabelsClearsTheExplicitSelection() {
        val current = HistoryFilter(labels = setOf("person"))

        val applied = HistoryFilter.apply(current, HistoryFilter(labels = null))

        assertNull(applied.labels)
    }

    @Test
    fun theLastSelectedLabelCannotBeRemoved() {
        val current = HistoryFilter(labels = setOf("person"))

        val applied = HistoryFilter.apply(current, HistoryFilter(labels = emptySet()))

        assertEquals(setOf("person"), applied.labels)
    }

    @Test
    fun aSelectionOfSeveralLabelsCanBeReducedToOne() {
        val current = HistoryFilter(labels = setOf("person", "car"))

        val applied = HistoryFilter.apply(current, HistoryFilter(labels = setOf("car")))

        assertEquals(setOf("car"), applied.labels)
    }

    @Test
    fun applyTakesTheDraftOfTheOtherFields() {
        val current = HistoryFilter(labels = setOf("person"), zones = setOf("porch"))

        val applied = HistoryFilter.apply(
            current,
            HistoryFilter(labels = setOf("car"), zones = setOf("yard"), severities = setOf("detection")),
        )

        assertEquals(setOf("car"), applied.labels)
        assertEquals(setOf("yard"), applied.zones)
        assertEquals(setOf("detection"), applied.severities)
    }

    @Test
    fun resetReturnsTheInitialFilter() {
        HistoryFilter.apply(
            HistoryFilter(labels = setOf("person"), zones = setOf("porch"), severities = setOf("alert")),
            HistoryFilter(labels = setOf("car"), zones = setOf("yard"), severities = setOf("detection")),
        )

        val reset = HistoryFilter.reset()

        assertNull(reset.labels)
        assertNull(reset.zones)
        assertEquals(HistoryFilter.ALL_SEVERITIES, reset.severities)
    }

    @Test
    fun theFilterIsAboutLabelsZonesAndSeverityAndNotAboutTheCamera() {
        val filter = HistoryFilter(labels = setOf("person"), severities = setOf("alert"))

        assertTrue(filter.matches(review(camera = "front_door")))
        assertTrue(filter.matches(review(camera = "back_door")))
    }

    @Test
    fun aReviewOfAnotherLabelDoesNotMatch() {
        val filter = HistoryFilter(labels = setOf("person"))

        assertFalse(filter.matches(review(labels = listOf("car"))))
    }

    @Test
    fun aReviewOfAnotherSeverityDoesNotMatch() {
        val filter = HistoryFilter(severities = setOf("alert"))

        assertFalse(filter.matches(review(severity = "detection")))
    }

    @Test
    fun aReviewOfAnotherZoneDoesNotMatch() {
        val filter = HistoryFilter(zones = setOf("porch"))

        assertFalse(filter.matches(review(zones = listOf("yard"))))
    }

    @Test
    fun theEmptyFilterKeepsEveryReview() {
        val filter = HistoryFilter.reset()

        assertTrue(filter.matches(review(severity = "significant_motion", labels = listOf("dog"), zones = listOf("yard"))))
    }
}
