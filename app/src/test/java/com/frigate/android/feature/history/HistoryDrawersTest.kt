package com.frigate.android.feature.history

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryDrawersTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun theCalendarDayOfThePickerCoversTheWholeDay() {
        val range = HistoryRange.daySeconds(LocalDate.of(2026, 10, 30), zone)

        assertEquals(
            ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, zone).toEpochSecond().toDouble(),
            range.after,
            0.001,
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, zone).toEpochSecond().toDouble(),
            range.before,
            0.001,
        )
    }

    @Test
    fun theCalendarDayOfThePickerFollowsTheOffsetOfTheZone() {
        val berlin = ZoneId.of("Europe/Berlin")

        val range = HistoryRange.daySeconds(LocalDate.of(2026, 10, 30), berlin)

        val start = ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, berlin).toEpochSecond().toDouble()
        assertEquals(start, range.after, 0.001)
    }

    @Test
    fun theWindowOfTheLastDayIsNamedAfterItsLength() {
        val range = HistoryRange.initial(nowSeconds = 1_700_000_000.0)

        assertEquals("Last 24 hours", historyRangeLabel(range, range, zone))
    }

    @Test
    fun theWindowOfACalendarDayIsNamedAfterItsDate() {
        val initial = HistoryRange.initial(nowSeconds = 1_700_000_000.0)
        val day = HistoryRange.daySeconds(LocalDate.of(2026, 10, 30), zone)

        assertEquals("2026-10-30", historyRangeLabel(day, initial, zone))
    }

    @Test
    fun thePanelsAreNamedInTheLanguageOfTheApp() {
        assertEquals("Timeline", HistoryPanelMode.TIMELINE.label())
        assertEquals("Events", HistoryPanelMode.EVENTS.label())
        assertEquals("Detail", HistoryPanelMode.DETAIL.label())
    }
}
