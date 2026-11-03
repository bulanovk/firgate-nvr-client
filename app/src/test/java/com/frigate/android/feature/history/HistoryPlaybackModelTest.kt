package com.frigate.android.feature.history

import com.frigate.android.core.server.TimelineSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HistoryPlaybackModelTest {
    private fun span(start: Double, end: Double, durationSeconds: Double) =
        TimelineSpan(startTime = start, endTime = end, durationMs = (durationSeconds * 1000).toInt())

    @Test
    fun theWindowOfThePlaylistIsTheHourChunkOfTheAnchor() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 3_600.0),
            HistoryRange(after = 3_600.0, before = 7_200.0),
        )
        val spans = listOf(span(0.0, 3_600.0, 3_600.0), span(3_600.0, 7_200.0, 3_600.0))

        val window = historyWindow(chunks, anchorSeconds = 1_800.0, spans = spans)!!

        assertEquals(chunks[0], window.chunk)
        assertEquals(listOf(spans[0]), window.spans)
    }

    @Test
    fun theWindowStaysTheSameWhileTheSeekStaysInsideTheHourChunk() {
        // The manifest of the playlist must not be requested again for every seek: the window of
        // the vod endpoint is the hour chunk, not the moment of the playhead.
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 3_600.0),
            HistoryRange(after = 3_600.0, before = 7_200.0),
        )
        val spans = listOf(span(0.0, 3_600.0, 3_600.0), span(3_600.0, 7_200.0, 3_600.0))

        val first = historyWindow(chunks, anchorSeconds = 1_800.0, spans = spans)!!
        val second = historyWindow(chunks, anchorSeconds = 2_100.0, spans = spans)!!

        assertEquals(first.chunk, second.chunk)
        assertEquals(first.inpointOffset, second.inpointOffset, 0.001)
    }

    @Test
    fun theWindowMovesToTheNextChunkWhenTheSeekCrossesTheHour() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 3_600.0),
            HistoryRange(after = 3_600.0, before = 7_200.0),
        )
        val spans = listOf(span(0.0, 3_600.0, 3_600.0), span(3_600.0, 7_200.0, 3_600.0))

        val window = historyWindow(chunks, anchorSeconds = 3_700.0, spans = spans)!!

        assertEquals(chunks[1], window.chunk)
        assertEquals(listOf(spans[1]), window.spans)
    }

    @Test
    fun theWindowTrimsTheSpansToTheChunkAndCountsTheTrimmedLeadIn() {
        // The vod endpoint trims the first segment of the hour, so the playlist starts later than
        // the requested window.
        val chunk = HistoryRange(after = 600.0, before = 3_600.0)
        val spans = listOf(span(0.0, 1_200.0, 1_200.0), span(1_200.0, 3_600.0, 2_400.0))

        val window = historyWindow(listOf(chunk), anchorSeconds = 1_800.0, spans = spans)!!

        assertEquals(600.0, window.inpointOffset, 0.001)
        assertEquals(2, window.spans.size)
        assertEquals(1_200.0, historySeekPositionInWindow(1_800.0, window)!!, 0.001)
    }

    @Test
    fun aRebuiltSourceContinuesAtThePlayheadAndASeekLandsOnTheAnchor() {
        // A reload of the archive must not rewind the playback: the source continues at the
        // playhead, an explicit seek of the user lands on the anchor of the session.
        val chunk = HistoryRange(after = 0.0, before = 3_600.0)
        val window = historyWindow(listOf(chunk), anchorSeconds = 1_800.0, spans = listOf(span(0.0, 3_600.0, 3_600.0)))!!

        val positions = historyPositions(anchorSeconds = 1_800.0, playheadSeconds = 1_200.0, window = window)

        assertEquals(1_200.0, positions.start!!, 0.001)
        assertEquals(1_800.0, positions.seek!!, 0.001)
    }

    @Test
    fun thePositionsAreUnknownWithoutAWindow() {
        val positions = historyPositions(anchorSeconds = 1_800.0, playheadSeconds = 1_200.0, window = null)

        assertNull(positions.start)
        assertNull(positions.seek)
    }

    @Test
    fun thePlaybackContinuesInTheNextChunkWhenTheRecordingEnds() {
        // The PWA moves on to the next hour chunk of the window when the clip ends and plays on.
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 3_600.0),
            HistoryRange(after = 3_600.0, before = 7_200.0),
        )

        assertEquals(chunks[1], nextChunkForPlayback(chunks, anchorSeconds = 1_800.0))
    }

    @Test
    fun thePlaybackStopsAtTheLastChunkOfTheWindow() {
        val chunks = listOf(
            HistoryRange(after = 0.0, before = 3_600.0),
            HistoryRange(after = 3_600.0, before = 7_200.0),
        )

        assertNull(nextChunkForPlayback(chunks, anchorSeconds = 3_700.0))
    }

    @Test
    fun theSeekPositionSumsTheDurationsOfTheEarlierSegments() {
        val segments = listOf(span(100.0, 160.0, 60.0), span(170.0, 200.0, 30.0))

        assertEquals(65.0, historySeekPosition(175.0, segments, inpointOffset = 0.0))
    }

    @Test
    fun theSeekPositionAccountsForTheKeyframeLeadIn() {
        val segments = listOf(span(100.0, 160.0, 65.0))

        assertEquals(15.0, historySeekPosition(110.0, segments, inpointOffset = 0.0))
    }

    @Test
    fun theInpointOffsetCoversTheTrimmedFirstSegment() {
        assertEquals(10.0, historyInpointOffset(110.0, span(100.0, 160.0, 60.0)))
        assertEquals(0.0, historyInpointOffset(110.0, span(100.0, 105.0, 5.0)))
        assertEquals(0.0, historyInpointOffset(110.0, null))
    }

    @Test
    fun theSeekPositionIsUndefinedOutsideTheTimeline() {
        val segments = listOf(span(100.0, 160.0, 60.0))

        assertNull(historySeekPosition(50.0, segments, inpointOffset = 0.0))
        assertNull(historySeekPosition(300.0, segments, inpointOffset = 0.0))
        assertNull(historySeekPosition(120.0, emptyList(), inpointOffset = 0.0))
    }

    @Test
    fun theInpointOffsetIsSubtractedFromTheSeekPosition() {
        val segments = listOf(span(100.0, 160.0, 60.0))

        assertEquals(10.0, historySeekPosition(115.0, segments, inpointOffset = 5.0))
    }

    @Test
    fun theWallClockIsRecoveredFromThePlayerPosition() {
        val segments = listOf(span(100.0, 160.0, 60.0), span(170.0, 200.0, 30.0))

        assertEquals(175.0, historyWallClockFor(65.0, segments))
        assertEquals(130.0, historyWallClockFor(30.0, segments))
    }

    @Test
    fun theWallClockIsRecoveredAfterASeekIntoATrimmedPlaylist() {
        // The playlist starts at 110 s and trims the first segment, which began at 100 s.
        val segments = listOf(span(100.0, 160.0, 60.0), span(170.0, 200.0, 30.0))
        val inpointOffset = historyInpointOffset(110.0, segments.first())

        val position = historySeekPosition(175.0, segments, inpointOffset)!!

        assertEquals(175.0, historyWallClockFor(position, segments, inpointOffset))
    }

    @Test
    fun theWallClockIsClampedToTheEndOfTheTimeline() {
        val segments = listOf(span(100.0, 160.0, 60.0))

        assertEquals(160.0, historyWallClockFor(1_000.0, segments))
    }

    @Test
    fun segmentsWithoutMediaAreSkipped() {
        val segments = listOf(span(100.0, 160.0, 0.0), span(170.0, 200.0, 30.0))

        assertEquals(5.0, historySeekPosition(175.0, segments, inpointOffset = 0.0))
    }
}
