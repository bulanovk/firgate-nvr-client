package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.FrigateReviewSummary

internal enum class EventsType { ALERT, DETECTION, MOTION }

internal data class EventsRange(val after: Double, val before: Double, val dayKey: String?) {
    init { require(after.isFinite() && before.isFinite() && before > after) }
}

internal data class EventsQuery(
    val cameraIds: List<String>,
    val range: EventsRange,
    val timezone: String,
    val labels: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
)

internal data class EventsFilter(
    val type: EventsType = EventsType.ALERT,
    val showReviewed: Boolean = false,
    val includeOtherSeverity: Boolean = false,
)

internal data class EventsCatalog(
    val cameras: Map<String, String> = emptyMap(),
    val labels: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    val role: String = "viewer",
    val cameraAspects: Map<String, Double> = emptyMap(),
)

internal fun eventsCatalog(
    cameras: List<com.frigate.android.core.server.FrigateCamera>,
    role: String,
    selectedCameraIds: List<String> = cameras.map { it.id },
): EventsCatalog = EventsCatalog(
    cameras = cameras.associate { it.id to it.name },
    labels = cameras.filter { it.id in selectedCameraIds && it.id != "birdseye" }.flatMap { it.reviewLabels }.distinct().sorted(),
    zones = cameras.filter { it.id in selectedCameraIds && it.id != "birdseye" }.flatMap { it.reviewZones }.distinct().sorted(),
    role = role,
    cameraAspects = cameras.associate { camera ->
        camera.id to if (camera.detectWidth > 0 && camera.detectHeight > 0)
            camera.detectWidth.toDouble() / camera.detectHeight else 16.0 / 9
    },
)

internal sealed interface EventsAction {
    data class SelectType(val type: EventsType) : EventsAction
    data class SetCameras(val ids: List<String>) : EventsAction
    data class SetDate(val dayKey: String?) : EventsAction
    data class SetReviewedFilter(val reviewed: Boolean) : EventsAction
    /** Labels, zones and the severity switch travel together: one Apply is one filter change. */
    data class SetFilters(val labels: List<String>, val zones: List<String>, val includeOtherSeverity: Boolean) : EventsAction
    data class ToggleSelection(val id: String) : EventsAction
    data object ClearSelection : EventsAction
    data class MarkViewed(val ids: List<String>, val reviewed: Boolean) : EventsAction
    data class Preview(val id: String?) : EventsAction
    data object ReloadNewItems : EventsAction
    data object RetryFailedAction : EventsAction
    data object ExportSelection : EventsAction
    data object DeleteSelection : EventsAction
}

internal data class EventsSnapshot(val items: List<FrigateReview>, val summary: FrigateReviewSummary)
internal data class ReviewOpenRequest(val review: FrigateReview, val range: EventsRange)
internal data class ReviewPreview(val cameraId: String, val startTime: Double, val endTime: Double, val src: String)
internal data class PreviewFrame(val cameraId: String, val timestamp: Double, val src: String)
internal data class MotionRecording(val startTime: Double, val endTime: Double, val heatmap: Map<Int, Int>)
internal data class ExportJob(val id: String, val status: String, val downloadPath: String? = null)
internal data class ExportBatchResult(val succeeded: Map<String, ExportJob>, val failed: Map<String, String>)
internal data class ExportCase(val id: String, val name: String, val description: String?)
internal data class ExportCaseChoice(val id: String? = null, val name: String? = null, val description: String? = null) {
    init { require((!id.isNullOrBlank()) xor (!name.isNullOrBlank())) }
}
