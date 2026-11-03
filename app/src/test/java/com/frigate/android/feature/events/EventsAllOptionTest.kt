package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventsAllOptionTest {
    /** The reference "All" row is a reset: switching it on clears the selection, switching it off does nothing. */
    @Test fun allOptionOnClearsSelectionAndOffKeepsIt() {
        assertEquals(emptySet<String>(), allOptionSelection(selected = setOf("car", "person"), on = true))
        assertEquals(setOf("car"), allOptionSelection(selected = setOf("car"), on = false))
    }
}
