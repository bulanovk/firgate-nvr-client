package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateCamera

internal enum class RecordingIndicatorState {
    ACTIVE,
    ENABLED,
    DISABLED,
}

internal fun recordingIndicatorState(
    recordEnabled: Boolean,
    activeRecording: Boolean,
): RecordingIndicatorState = when {
    !recordEnabled -> RecordingIndicatorState.DISABLED
    activeRecording -> RecordingIndicatorState.ACTIVE
    else -> RecordingIndicatorState.ENABLED
}

internal sealed interface CameraIndicator {
    data object Recording : CameraIndicator
    data class Object(val label: String) : CameraIndicator
}

internal fun cameraIndicators(camera: FrigateCamera, activity: CameraActivity): List<CameraIndicator> = buildList {
    if (activity.recordingEnabled ?: camera.recordEnabled) add(CameraIndicator.Recording)
    if (activity.detectionEnabled ?: camera.detectEnabled) {
        activity.displayedObjects.sorted().forEach { add(CameraIndicator.Object(it)) }
    }
}
