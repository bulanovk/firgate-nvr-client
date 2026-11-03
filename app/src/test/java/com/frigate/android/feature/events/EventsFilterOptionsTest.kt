package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventsFilterOptionsTest {
    private fun review(objects: List<String> = emptyList(), audio: List<String> = emptyList(),
        zones: List<String> = emptyList(), sub: List<String> = emptyList()) =
        FrigateReview("id", "cam", 0.0, 1.0, "alert", false, null, objects, zones, audio, sub)

    @Test fun labelsAndZonesComeOnlyFromLoadedRecordsInInsertionOrder() {
        val options = eventsFilterOptions(listOf(
            review(objects = listOf("car", "person"), zones = listOf("porch")),
            review(objects = listOf("person", "cat"), audio = listOf("bark"), zones = listOf("garden", "porch")),
        ))
        assertEquals(listOf("car", "person", "cat", "bark"), options.labels)
        assertEquals(listOf("porch", "garden"), options.zones)
    }

    @Test fun verifiedSuffixIsStrippedAndSubLabelsAreIgnored() {
        val options = eventsFilterOptions(listOf(review(objects = listOf("person-verified", "person"), sub = listOf("Bob"))))
        assertEquals(listOf("person"), options.labels)
    }

    @Test fun noRecordsGiveNoOptions() {
        val options = eventsFilterOptions(emptyList())
        assertEquals(emptyList<String>(), options.labels)
        assertEquals(emptyList<String>(), options.zones)
    }

    @Test fun lastSelectedValueCannotBeUnchecked() {
        assertEquals(setOf("car"), toggleFilterValue(setOf("car"), "car", on = false))
        assertEquals(setOf("car"), toggleFilterValue(setOf("car", "person"), "person", on = false))
        assertEquals(setOf("car", "person"), toggleFilterValue(setOf("car"), "person", on = true))
    }
}
