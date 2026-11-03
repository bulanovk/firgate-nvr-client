package com.frigate.android.feature.live

internal fun shouldRunLiveResources(isActivityStarted: Boolean, isLiveScreen: Boolean): Boolean =
    isActivityStarted && isLiveScreen

internal enum class CameraPlaybackAction {
    START,
    SNAPSHOT,
    PAUSE,
    DEFER,
}

internal fun shouldStreamCamera(isVisible: Boolean, hasActivity: Boolean): Boolean =
    isVisible && hasActivity

internal fun shouldShowSnapshot(isVisible: Boolean, hasActivity: Boolean): Boolean =
    isVisible && !hasActivity

internal fun cameraPlaybackAction(
    isVisible: Boolean,
    hasActivity: Boolean,
    isScrolling: Boolean,
): CameraPlaybackAction = when {
    isScrolling -> CameraPlaybackAction.DEFER
    shouldStreamCamera(isVisible, hasActivity) -> CameraPlaybackAction.START
    shouldShowSnapshot(isVisible, hasActivity) -> CameraPlaybackAction.SNAPSHOT
    else -> CameraPlaybackAction.PAUSE
}
