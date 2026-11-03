package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class EventsEmptyStateTest {
    @Test
    fun theEmptyStateNamesTheSeverityAndTellsAFilteredListApart() {
        assertEquals("No alerts to review", eventsEmptyMessage(EventsType.ALERT, filtered = false))
        assertEquals("There are no alerts to review", eventsEmptyMessage(EventsType.ALERT, filtered = true))
        assertEquals("No detections to review", eventsEmptyMessage(EventsType.DETECTION, filtered = false))
        assertEquals("There are no detections to review", eventsEmptyMessage(EventsType.DETECTION, filtered = true))
    }
}
