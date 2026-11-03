package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.RecordingCoverage
import com.frigate.android.core.server.UnavailableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The biggest number of review segments the archive asks for.
 *
 * The server has no default limit: an unbounded query over a day of a busy camera answers with tens
 * of megabytes of segments, and the phone has to hold them in memory. The limit keeps the answer
 * of the server inside the memory of the app; the server answers with the newest segments of the
 * window, so the events around the playhead are the ones that stay.
 */
internal const val HISTORY_REVIEW_LIMIT = 2_000

/**
 * Archive data of one camera: review segments, motion activity, recording gaps and the recording
 * coverage with the realized playlist timelines.
 *
 * The server resamples the motion activity to half a segment and the recording gaps to a full
 * segment, so the caller passes the segment duration of the current zoom level. The requested
 * window is aligned to that grid before it reaches the server, exactly like the PWA does.
 */
internal class HistoryDataSource(private val client: FrigateClient) {
    suspend fun reviews(camera: String, range: HistoryRange): List<FrigateReview> = io {
        client.reviews(
            cameraIds = listOf(camera),
            before = range.before,
            after = range.after,
            limit = HISTORY_REVIEW_LIMIT,
        )
    }

    suspend fun motion(camera: String, range: HistoryRange, segmentDuration: Double): List<MotionPoint> = io {
        client.reviewMotion(
            camera = camera,
            before = alignEnd(range.before, segmentDuration),
            after = alignStart(range.after, segmentDuration),
            scale = Math.round(segmentDuration / 2.0).toInt(),
        )
    }

    suspend fun unavailable(
        camera: String,
        range: HistoryRange,
        segmentDuration: Double,
    ): List<UnavailableSpan> = io {
        client.unavailableRecordings(
            camera = camera,
            before = alignEnd(range.before, segmentDuration),
            after = alignStart(range.after, segmentDuration),
            scale = Math.round(segmentDuration).toInt(),
        )
    }

    suspend fun coverage(
        camera: String,
        range: HistoryRange,
        segmentDuration: Double,
    ): RecordingCoverage = io {
        client.recordingCoverage(
            camera = camera,
            before = alignEnd(range.before, segmentDuration),
            after = alignStart(range.after, segmentDuration),
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
