package com.frigate.android.core.server

data class FrigateCamera(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val streamName: String = id,
    val recordEnabled: Boolean = false,
    val detectEnabled: Boolean = true,
    val detectWidth: Int = 0,
    val detectHeight: Int = 0,
    val streams: Map<String, String> = mapOf(id to streamName),
    val enabledInConfig: Boolean = enabled,
    val recordEnabledInConfig: Boolean = recordEnabled,
    val snapshotsEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
    val audioEnabledInConfig: Boolean = audioEnabled,
    val transcriptionEnabled: Boolean = false,
    val transcriptionEnabledInConfig: Boolean = transcriptionEnabled,
    val autotrackingEnabled: Boolean = false,
    val autotrackingEnabledInConfig: Boolean = autotrackingEnabled,
    val hasOnvif: Boolean = false,
    val reviewLabels: List<String> = emptyList(),
    val reviewZones: List<String> = emptyList(),
)

data class FrigateConfiguration(val cameras: List<FrigateCamera>, val uiTimezone: String? = null)

data class LiveStreamInfo(val audioOutput: Boolean = false, val twoWayAudio: Boolean = false)

data class FrigateEvent(
    val id: String,
    val camera: String,
    val label: String,
    val startTime: Double,
    val endTime: Double?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
)

/** A Frigate review segment: the contract shared by the Live carousel and the History screen. */
data class FrigateReview(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    val severity: String,
    val hasBeenReviewed: Boolean,
    val thumbnailPath: String?,
    val objectLabels: List<String>,
    val zones: List<String> = emptyList(),
    val audioLabels: List<String> = emptyList(),
    val subLabels: List<String> = emptyList(),
    val detections: List<String> = emptyList(),
    val threatLevel: Int? = null,
    val title: String? = null,
) {
    val isOngoing: Boolean get() = endTime == null
}

data class FrigateReviewCounts(
    val totalAlert: Int,
    val reviewedAlert: Int,
    val totalDetection: Int,
    val reviewedDetection: Int,
)

data class FrigateReviewSummary(
    val last24Hours: FrigateReviewCounts?,
    val days: Map<String, FrigateReviewCounts>,
)

/** Motion activity of one camera, resampled by the server to the requested scale. */
data class MotionPoint(
    val startTime: Double,
    val motion: Double,
    val camera: String,
)

/** A window without any recording. The server does not name the camera, so the caller supplies it. */
data class UnavailableSpan(
    val camera: String,
    val startTime: Double,
    val endTime: Double,
)

/** A window covered by recordings, with the streams that recorded it. */
data class CoverageSpan(
    val startTime: Double,
    val endTime: Double,
    val streams: List<String>,
)

/**
 * One span of a realized vod playlist: wall-clock bounds plus the playlist duration in
 * milliseconds. The duration exceeds the wall length when the clip carries keyframe back-snap
 * lead-in, and is zero when the clip is skipped by the playlist.
 */
data class TimelineSpan(
    val startTime: Double,
    val endTime: Double,
    val durationMs: Int,
) {
    val durationSeconds: Double get() = durationMs / 1000.0
}

/** Recording coverage of one camera including the realized playlist timelines. */
data class RecordingCoverage(
    val spans: List<CoverageSpan>,
    val timelines: Map<String, List<TimelineSpan>>,
)
