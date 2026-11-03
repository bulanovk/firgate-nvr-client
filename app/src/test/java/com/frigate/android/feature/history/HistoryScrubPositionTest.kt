package com.frigate.android.feature.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryScrubPositionTest {
    @Test
    fun `dragging down moves the cursor to an earlier moment by rows times step`() {
        // 40 px at 8 px per row is 5 rows of 10 s: the finger moved the cursor back by 50 s.
        assertEquals(950.0, historyScrubPosition(base = 1_000.0, dragPx = 40f, rowHeightPx = 8f, step = 10.0))
    }

    @Test
    fun `the position depends on the start of the gesture and the total drag only`() {
        // The drag callback reports the total since the start of the gesture: the answer for a total
        // must not change with how many events came before it.
        val once = historyScrubPosition(1_000.0, 24f, 8f, 10.0)
        val again = historyScrubPosition(1_000.0, 24f, 8f, 10.0)
        assertEquals(once, again)
        assertEquals(970.0, once)
    }

    @Test
    fun `dragging up moves the cursor to a later moment`() {
        assertEquals(1_030.0, historyScrubPosition(1_000.0, -24f, 8f, 10.0))
    }
}
