package com.frigate.android.feature.history

import com.frigate.android.core.server.TimelineSpan
import kotlin.math.max

/**
 * The window of the playlist of the archive player.
 *
 * The window is the hour chunk that holds the anchor, exactly like the PWA keeps the whole hour
 * chunk loaded and seeks inside it. The anchor only moves on an explicit seek, so the playlist stays
 * open while the playback runs.
 */
internal data class HistoryWindow(
    val chunk: HistoryRange,
    val spans: List<TimelineSpan>,
    val inpointOffset: Double,
)

/**
 * The window of the hour chunk that holds the anchor.
 *
 * The realized timelines of the coverage are cut to the chunk: the vod endpoint is asked for the
 * chunk alone, so the playlist never covers more than an hour of the recording.
 */
internal fun historyWindow(
    chunks: List<HistoryRange>,
    anchorSeconds: Double,
    spans: List<TimelineSpan>,
): HistoryWindow? {
    val chunk = chunkFor(chunks, anchorSeconds) ?: return null
    val window = spans.filter { it.startTime < chunk.before && it.endTime > chunk.after }
    return HistoryWindow(
        chunk = chunk,
        spans = window,
        inpointOffset = historyInpointOffset(chunk.after, window.firstOrNull()),
    )
}

/**
 * The chunk the playback continues in when the recording of the current one ends.
 *
 * The PWA moves on to the next hour chunk of the window and plays on; the last chunk of the
 * window ends the playback.
 */
internal fun nextChunkForPlayback(chunks: List<HistoryRange>, anchorSeconds: Double): HistoryRange? {
    val index = chunkIndex(chunks, anchorSeconds)
    if (index < 0) return null
    return chunks.getOrNull(index + 1)
}

/** The position of the playhead inside the window, used when the source is rebuilt. */
internal fun historyStartPosition(playheadSeconds: Double, window: HistoryWindow): Double? =
    historySeekPosition(playheadSeconds, window.spans, window.inpointOffset)

/** The position of the anchor inside the window, used when the user seeks. */
internal fun historySeekPositionInWindow(anchorSeconds: Double, window: HistoryWindow): Double? =
    historySeekPosition(anchorSeconds, window.spans, window.inpointOffset)

/**
 * The two positions of the player of the archive.
 *
 * A rebuilt source continues at the playhead, an explicit seek of the user lands on the anchor of
 * the session. Both are positions inside the playlist of the window.
 */
internal data class HistoryPositions(val start: Double?, val seek: Double?)

/** The positions of the player for the window of the playlist. */
internal fun historyPositions(
    anchorSeconds: Double,
    playheadSeconds: Double,
    window: HistoryWindow?,
): HistoryPositions {
    if (window == null) return HistoryPositions(start = null, seek = null)
    return HistoryPositions(
        start = historyStartPosition(playheadSeconds, window),
        seek = historySeekPositionInWindow(anchorSeconds, window),
    )
}

/**
 * The offset of the first segment of the playlist.
 *
 * The vod endpoint trims the first segment of the hour, so the playlist starts later than the
 * requested window and the difference has to be subtracted from every seek.
 */
internal fun historyInpointOffset(timeRangeStart: Double, firstSegment: TimelineSpan?): Double {
    if (firstSegment == null) return 0.0
    return if (firstSegment.startTime < timeRangeStart && firstSegment.endTime > timeRangeStart) {
        timeRangeStart - firstSegment.startTime
    } else {
        0.0
    }
}

/**
 * The player position of a wall-clock moment.
 *
 * The playlist is a concatenation of the recording segments, so the position is the sum of the
 * durations of the segments before the target. A segment can carry more media than its wall
 * length because of the keyframe back-snap lead-in, and that lead-in is part of the position.
 */
internal fun historySeekPosition(
    timestamp: Double,
    segments: List<TimelineSpan>,
    inpointOffset: Double,
): Double? {
    if (segments.isEmpty()) return null
    val first = segments.first()
    val last = segments.last()
    if (timestamp < first.startTime || timestamp > last.endTime) return null

    var seekSeconds = 0.0
    for (segment in segments) {
        if (segment.startTime > timestamp) break
        if (segment.durationSeconds == 0.0) continue

        val wallLength = segment.endTime - segment.startTime
        val leadIn = max(0.0, segment.durationSeconds - wallLength)
        if (segment.endTime < timestamp) {
            seekSeconds += segment.durationSeconds
            continue
        }
        seekSeconds += leadIn + (timestamp - segment.startTime)
        break
    }

    seekSeconds -= inpointOffset
    return if (seekSeconds >= 0) seekSeconds else null
}

/**
 * The wall-clock moment of a player position, the inverse of [historySeekPosition].
 *
 * The playlist starts at the trimmed beginning of its first segment, so the media of that segment
 * is shorter by the inpoint offset and the wall-clock of the playlist begins at the trim. The
 * offset has to be given back here, otherwise the playhead drifts away from the moment the user
 * asked for.
 */
internal fun historyWallClockFor(
    playerSeconds: Double,
    segments: List<TimelineSpan>,
    inpointOffset: Double = 0.0,
): Double {
    var totalTime = 0.0
    for ((index, segment) in segments.withIndex()) {
        val wallLength = segment.endTime - segment.startTime
        val leadIn = max(0.0, segment.durationSeconds - wallLength)
        val media = if (index == 0) segment.durationSeconds - inpointOffset else segment.durationSeconds
        val wallStart = if (index == 0) segment.startTime + inpointOffset else segment.startTime
        if (totalTime + media > playerSeconds) {
            return wallStart + max(0.0, playerSeconds - totalTime - leadIn)
        }
        totalTime += media
    }
    return segments.lastOrNull()?.endTime ?: 0.0
}
