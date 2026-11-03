package com.frigate.android.feature.live

/**
 * A choice of the camera settings.
 *
 * An option the app cannot use yet stays in the list, dimmed, with the reason: it is not removed
 * silently, the user sees that the choice exists and why it is not theirs to make.
 */
internal data class CameraSettingOption(
    val label: String,
    val value: String,
    val reason: String? = null,
) {
    /** An option that explains itself cannot be picked. */
    val available: Boolean get() = reason == null
}

/**
 * The playback engines of the PWA: MSE always, WebRTC while the app has a WebRTC path.
 *
 * The PWA keeps an unavailable engine in the list, dimmed, with the reason it cannot be used.
 * JSMpeg is not an engine: there it is the force low-bandwidth switch, and the app has neither.
 */
internal fun liveEngineOptions(): List<CameraSettingOption> = listOf(
    CameraSettingOption("MSE", "mse"),
    CameraSettingOption("WebRTC", "webrtc", "The app has no WebRTC playback yet"),
)
