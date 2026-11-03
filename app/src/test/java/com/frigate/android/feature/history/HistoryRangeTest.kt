package com.frigate.android.feature.history

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HistoryRangeTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Double =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toEpochSecond().toDouble()

    @Test
    fun initialRangeCoversTheLastTwentyFourHours() {
        val range = HistoryRange.initial(nowSeconds = 1_700_000_000.0)

        assertEquals(1_700_000_000.0 - 86_400.0, range.after, 0.001)
        assertEquals(1_700_000_000.0, range.before, 0.001)
    }

    @Test
    fun initialRangeHonoursTheRequestedWindowLength() {
        val range = HistoryRange.initial(nowSeconds = 1_700_000_000.0, hours = 2)

        assertEquals(1_700_000_000.0 - 7_200.0, range.after, 0.001)
        assertEquals(1_700_000_000.0, range.before, 0.001)
    }

    @Test
    fun startPositionUsesTheEventStartInsideTheRange() {
        val range = HistoryRange(after = 1_000.0, before = 2_000.0)

        assertEquals(1_500.0, range.startPosition(1_500.0), 0.001)
    }

    @Test
    fun startPositionFallsBackToTheLastMinuteOutsideTheRange() {
        val range = HistoryRange(after = 1_000.0, before = 2_000.0)

        assertEquals(1_940.0, range.startPosition(500.0), 0.001)
        assertEquals(1_940.0, range.startPosition(2_500.0), 0.001)
    }

    @Test
    fun chunkedRangeSplitsTheWindowIntoHourChunks() {
        val range = HistoryRange(after = at(2026, 10, 30, 12, 5), before = at(2026, 10, 30, 14, 37, 20))

        val chunks = chunkedRange(range)

        assertEquals(3, chunks.size)
        assertEquals(at(2026, 10, 30, 12, 0), chunks[0].after, 0.001)
        assertEquals(at(2026, 10, 30, 13, 0), chunks[0].before, 0.001)
        assertEquals(at(2026, 10, 30, 13, 0), chunks[1].after, 0.001)
        assertEquals(at(2026, 10, 30, 14, 0), chunks[1].before, 0.001)
        assertEquals(at(2026, 10, 30, 14, 0), chunks[2].after, 0.001)
        assertEquals(Math.floor(at(2026, 10, 30, 14, 37, 20)), chunks[2].before, 0.001)
    }

    @Test
    fun chunkedRangeKeepsASingleChunkForAnHourWideWindow() {
        val range = HistoryRange(after = at(2026, 10, 30, 12, 5), before = at(2026, 10, 30, 12, 45))

        val chunks = chunkedRange(range)

        assertEquals(1, chunks.size)
        assertEquals(at(2026, 10, 30, 12, 0), chunks.single().after, 0.001)
        assertEquals(Math.floor(at(2026, 10, 30, 12, 45)), chunks.single().before, 0.001)
    }

    @Test
    fun chunkedRangeCutsTheWindowAtTheUtcHourLikeThePwa() {
        // The PWA zeroes the minutes of the UTC hour, so the boundaries do not follow the zone of
        // the device: in UTC+5:30 the chunks start at the local half hour (12:00 UTC is 17:30
        // there), while a cut at the local hour would start them at 11:30 UTC. The zone of the
        // device is changed for the time of the test, otherwise the check would pass on a machine
        // whose clock runs on UTC.
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val range = HistoryRange(after = at(2026, 10, 30, 12, 5), before = at(2026, 10, 30, 14, 37, 20))

            val chunks = chunkedRange(range)

            assertEquals(at(2026, 10, 30, 12, 0), chunks[0].after, 0.001)
            assertEquals(at(2026, 10, 30, 13, 0), chunks[0].before, 0.001)
            assertEquals(at(2026, 10, 30, 13, 0), chunks[1].after, 0.001)
            assertEquals(at(2026, 10, 30, 14, 0), chunks[1].before, 0.001)
            assertEquals(at(2026, 10, 30, 14, 0), chunks[2].after, 0.001)
            assertEquals(Math.floor(at(2026, 10, 30, 14, 37, 20)), chunks[2].before, 0.001)
            assertEquals(3, chunks.size)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun chunkIndexUsesHalfOpenChunksAndAClosedLastChunk() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 10.0),
            HistoryRange(after = 10.0, before = 20.0),
            HistoryRange(after = 20.0, before = 30.0),
        )

        assertEquals(0, chunkIndex(chunks, 0.0))
        assertEquals(0, chunkIndex(chunks, 9.999))
        assertEquals(1, chunkIndex(chunks, 10.0))
        assertEquals(1, chunkIndex(chunks, 19.5))
        assertEquals(2, chunkIndex(chunks, 20.0))
        assertEquals(2, chunkIndex(chunks, 30.0))
    }

    @Test
    fun chunkIndexReportsNoChunkOutsideTheWindow() {
        val chunks = listOf(HistoryRange(after = 0.0, before = 10.0))

        assertEquals(-1, chunkIndex(chunks, -1.0))
        assertEquals(-1, chunkIndex(chunks, 10.5))
        assertEquals(-1, chunkIndex(emptyList(), 5.0))
    }

    @Test
    fun chunkForReturnsTheChunkThatHoldsTheMoment() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 10.0),
            HistoryRange(after = 10.0, before = 20.0),
        )

        assertEquals(chunks[0], chunkFor(chunks, 0.0))
        assertEquals(chunks[0], chunkFor(chunks, 9.9))
        assertEquals(chunks[1], chunkFor(chunks, 10.0))
        assertEquals(chunks[1], chunkFor(chunks, 19.9))
    }

    @Test
    fun chunkForKeepsTheLastChunkForAMomentBehindItsEnd() {
        // The last chunk ends at the whole second of the end of the window, the playhead can be a
        // fraction of a second behind it and still belongs to that chunk.
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 10.0),
            HistoryRange(after = 10.0, before = 20.0),
        )

        assertEquals(chunks[1], chunkFor(chunks, 20.4))
    }

    @Test
    fun chunkForKeepsTheFirstChunkForAMomentBeforeTheWindow() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 10.0),
            HistoryRange(after = 10.0, before = 20.0),
        )

        assertEquals(chunks[0], chunkFor(chunks, -5.0))
    }

    @Test
    fun chunkForIsUndefinedWithoutChunks() {
        assertNull(chunkFor(emptyList(), 5.0))
    }

    @Test
    fun daySecondsCoversTheWholeCalendarDay() {
        val berlin = ZoneId.of("Europe/Berlin")

        val range = HistoryRange.daySeconds(at(2026, 10, 30, 12, 0), berlin)

        val start = ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, berlin).toEpochSecond().toDouble()
        val end = ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, berlin).toEpochSecond().toDouble()
        assertEquals(start, range.after, 0.001)
        assertEquals(end, range.before, 0.001)
    }
}
