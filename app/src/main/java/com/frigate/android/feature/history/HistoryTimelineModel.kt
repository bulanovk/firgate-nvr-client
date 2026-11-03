package com.frigate.android.feature.history

import com.frigate.android.core.server.CoverageSpan
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.UnavailableSpan
import kotlin.math.ceil

/** One zoom step of the archive timeline. */
internal data class TimelineZoom(
    val segmentDuration: Double,
    val timestampSpreadMinutes: Int,
)

/** The zoom levels of the PWA recording view, from the coarsest to the finest. */
internal val HISTORY_ZOOM_LEVELS = listOf(
    TimelineZoom(segmentDuration = 30.0, timestampSpreadMinutes = 15),
    TimelineZoom(segmentDuration = 15.0, timestampSpreadMinutes = 5),
    TimelineZoom(segmentDuration = 5.0, timestampSpreadMinutes = 1),
)

/** The three panels the History screen can show. */
internal enum class HistoryPanelMode { TIMELINE, EVENTS, DETAIL }

/** One row of the archive timeline. */
internal data class HistorySegment(
    val startTime: Double,
    val endTime: Double,
    val severity: String?,
    val reviewed: Boolean,
    val objectCount: Int,
    val firstHalfMotion: Double,
    val secondHalfMotion: Double,
    val hasRecording: Boolean,
    val subOnly: Boolean,
)

/**
 * The row the timeline scrolls to, or null when the playhead is already visible.
 *
 * The PWA scrolls the timeline only when the playhead leaves the visible range, so the scroll of
 * the user is not taken away while the playback runs.
 */
internal fun historyTimelineScrollTarget(
    playheadIndex: Int,
    visibleRange: IntRange?,
    halfViewportRows: Int,
): Int? {
    if (visibleRange != null && playheadIndex in visibleRange) return null
    return (playheadIndex - halfViewportRows).coerceAtLeast(0)
}

/**
 * Builds the rows of the archive timeline of one camera.
 *
 * The model is pure: it turns the review segments, the motion activity, the recording gaps and the
 * recording coverage of a camera into the rows the timeline draws, newest first.
 */
internal object HistoryTimelineModel {
    const val SEVERITY_ALERT = "alert"
    const val SEVERITY_DETECTION = "detection"
    const val SEVERITY_SIGNIFICANT_MOTION = "significant_motion"

    /** The higher the value the stronger the severity. */
    fun severityRank(severity: String?): Int = when (severity) {
        SEVERITY_ALERT -> 3
        SEVERITY_DETECTION -> 2
        SEVERITY_SIGNIFICANT_MOTION -> 1
        else -> 0
    }

    fun build(
        camera: String,
        reviews: List<FrigateReview>,
        motion: List<MotionPoint>,
        unavailable: List<UnavailableSpan>,
        coverage: List<CoverageSpan>,
        range: HistoryRange,
        segmentDuration: Double,
    ): List<HistorySegment> {
        val cameraReviews = reviews.filter { it.camera == camera }
        val cameraMotion = motion.filter { it.camera == camera }
        val cameraUnavailable = unavailable.filter { it.camera == camera }
        val half = segmentDuration / 2.0
        val motionByBucket = cameraMotion
            .groupBy({ motionBucket(it.startTime, half) }, { it.motion })
            .mapValues { (_, values) -> values.sum() }

        return segmentTimes(range, segmentDuration).map { segmentTime ->
            val covering = cameraReviews.filter { it.coversSegment(segmentTime, segmentDuration, range.before) }
            val severity = covering.maxByOrNull { severityRank(it.severity) }?.severity
            HistorySegment(
                startTime = segmentTime,
                endTime = segmentTime + segmentDuration,
                severity = severity,
                reviewed = covering.any { it.hasBeenReviewed },
                objectCount = covering.flatMap { it.objectLabels }.distinct().size,
                firstHalfMotion = motionByBucket[motionBucket(segmentTime, half)] ?: 0.0,
                secondHalfMotion = motionByBucket[motionBucket(segmentTime + half, half)] ?: 0.0,
                hasRecording = cameraUnavailable.none {
                    segmentTime >= it.startTime && segmentTime < it.endTime
                },
                subOnly = coverage.any {
                    segmentTime >= it.startTime && segmentTime < it.endTime && "main" !in it.streams
                },
            )
        }
    }

    /**
     * The segment times of the timeline, newest first. The PWA keeps two segments of headroom on
     * both ends of the range so the playhead always has room around it.
     */
    private fun segmentTimes(range: HistoryRange, segmentDuration: Double): List<Double> {
        val duration = range.before - range.after + 4 * segmentDuration
        val count = ceil(duration / segmentDuration).toInt().coerceAtLeast(0)
        val newest = alignStart(range.before, segmentDuration) + 2 * segmentDuration
        return List(count) { newest - it * segmentDuration }
    }

    /**
     * A review covers every segment its own interval touches, the way the PWA expands an event to
     * the segment grid before matching it against a segment.
     */
    private fun FrigateReview.coversSegment(segmentTime: Double, segmentDuration: Double, rangeEnd: Double): Boolean {
        val eventStart = alignStart(startTime, segmentDuration)
        val eventEnd = alignStart(endTime ?: rangeEnd, segmentDuration) + segmentDuration
        return segmentTime >= eventStart && segmentTime < eventEnd
    }

    /**
     * The bucket a motion point belongs to. The PWA works in integer milliseconds so that half
     * segments of a fractional length stay stable.
     */
    private fun motionBucket(timestamp: Double, halfDuration: Double): Double {
        val halfMillis = Math.round(halfDuration * 1000.0)
        if (halfMillis <= 0L) return timestamp
        val timestampMillis = Math.round(timestamp * 1000.0)
        return Math.round(timestampMillis.toDouble() / halfMillis) * halfMillis / 1000.0
    }
}
