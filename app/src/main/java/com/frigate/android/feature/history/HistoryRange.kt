package com.frigate.android.feature.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * A wall-clock window of the archive. [after] is the older bound and [before] the newer one; the
 * PWA calls the same pair a time range.
 */
internal data class HistoryRange(val after: Double, val before: Double) {
    fun contains(timestamp: Double): Boolean = timestamp >= after && timestamp <= before

    /**
     * Where playback starts for an event that starts at [startTime]. An event inside the window
     * opens at its own start, an event from outside falls back to the last minute of the window.
     */
    fun startPosition(startTime: Double): Double =
        if (contains(startTime)) startTime else before - 60.0

    companion object {
        /** Lead-in the PWA adds so a clip opens a few seconds before the object appears. */
        const val REVIEW_PADDING: Double = 4.0

        fun initial(nowSeconds: Double, hours: Int = 24): HistoryRange =
            HistoryRange(after = nowSeconds - hours * 3600.0, before = nowSeconds)

        /** The calendar day of [localEpochSeconds] in [zone], from midnight to the next midnight. */
        fun daySeconds(localEpochSeconds: Double, zone: ZoneId): HistoryRange =
            daySeconds(
                Instant.ofEpochSecond(localEpochSeconds.toLong()).atZone(zone).toLocalDate(),
                zone,
            )

        /** The calendar day of [date] in [zone], from midnight to the next midnight. */
        fun daySeconds(date: LocalDate, zone: ZoneId): HistoryRange {
            val start = date.atStartOfDay(zone).toEpochSecond().toDouble()
            val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()
            return HistoryRange(after = start, before = end)
        }
    }
}

/** The PWA chunks the day into at most 24 hour long windows plus the trailing partial hour. */
private const val MAX_HOURLY_CHUNKS = 24

/**
 * Splits [range] into hour chunks the way the PWA does: the first chunk starts at the hour of the
 * range start, the last one ends at the range end and the chunks are contiguous.
 *
 * The PWA cuts the day at the UTC hour (`setUTCMinutes(0, 0, 0)` in `getChunkedTimeDay`), so the
 * boundaries do not follow the zone of the device: in UTC+5:30 a chunk starts at the local half
 * hour. The app cuts at the same hours, and the chunk of a moment is the same one the PWA picks.
 */
internal fun chunkedRange(range: HistoryRange): List<HistoryRange> {
    val firstHour = floorToHour(range.after)
    val lastHour = floorToHour(range.before)
    val chunks = mutableListOf<HistoryRange>()
    var start = firstHour
    var cursor = firstHour
    while (chunks.size < MAX_HOURLY_CHUNKS) {
        val next = cursor + 3600.0
        if (next > lastHour) break
        chunks += HistoryRange(after = start, before = next)
        start = next
        cursor = next
    }
    chunks += HistoryRange(after = start, before = floor(range.before))
    return chunks
}

/**
 * Index of the chunk that contains [timestamp]. Chunks are half open `[after, before)` except the
 * last one, which stays closed so its terminal boundary is reachable.
 */
internal fun chunkIndex(chunks: List<HistoryRange>, timestamp: Double): Int {
    chunks.forEachIndexed { index, chunk ->
        val isLast = index == chunks.lastIndex
        val inside = chunk.after <= timestamp &&
            (if (isLast) chunk.before >= timestamp else chunk.before > timestamp)
        if (inside) return index
    }
    return -1
}

/**
 * The chunk that holds [timestamp].
 *
 * The last chunk ends at the whole second of the end of the window, so a playhead in the fraction
 * of a second behind it belongs to that chunk and not to the whole window: the vod endpoint would
 * answer a request for the whole range instead of an hour of the recording.
 */
internal fun chunkFor(chunks: List<HistoryRange>, timestamp: Double): HistoryRange? {
    if (chunks.isEmpty()) return null
    val index = chunkIndex(chunks, timestamp)
    if (index >= 0) return chunks[index]
    val last = chunks.last()
    return if (timestamp >= last.after) last else chunks.first()
}

/** Floors [timestamp] to the segment grid of [segmentDuration]. */
internal fun alignStart(timestamp: Double, segmentDuration: Double): Double =
    floor(timestamp / segmentDuration) * segmentDuration

/** Ceils [timestamp] to the segment grid of [segmentDuration]. */
internal fun alignEnd(timestamp: Double, segmentDuration: Double): Double =
    kotlin.math.ceil(timestamp / segmentDuration) * segmentDuration

/** Floors [timestamp] to the hour of the UTC timeline, the boundary the PWA cuts its day at. */
private fun floorToHour(timestamp: Double): Double =
    Instant.ofEpochSecond(timestamp.toLong())
        .truncatedTo(ChronoUnit.HOURS)
        .epochSecond
        .toDouble()
