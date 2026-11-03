package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateCamera
import com.google.gson.JsonObject

enum class CameraControl(val label: String, val topic: String, val snapshotKey: String) {
    CAMERA("Camera", "enabled", "enabled"),
    DETECTION("Object detection", "detect", "detect"),
    RECORDING("Recording", "recordings", "record"),
    SNAPSHOTS("Snapshots", "snapshots", "snapshots"),
    AUDIO_DETECTION("Audio detection", "audio", "audio"),
    TRANSCRIPTION("Transcription", "audio_transcription", "audio_transcription"),
    AUTOTRACKING("Autotracking", "ptz_autotracker", "autotracking");

    fun command(camera: String, enabled: Boolean): String {
        require(camera.isNotBlank() && '/' !in camera && '\u0000' !in camera)
        return JsonObject().apply {
            addProperty("topic", "$camera/$topic/set")
            addProperty("payload", if (enabled) "ON" else "OFF")
            addProperty("retain", false)
        }.toString()
    }

    fun configuredValue(camera: FrigateCamera): Boolean = when (this) {
        CAMERA -> camera.enabled
        DETECTION -> camera.detectEnabled
        RECORDING -> camera.recordEnabled
        SNAPSHOTS -> camera.snapshotsEnabled
        AUDIO_DETECTION -> camera.audioEnabled
        TRANSCRIPTION -> camera.transcriptionEnabled
        AUTOTRACKING -> camera.autotrackingEnabled
    }
}

internal fun cameraControls(camera: FrigateCamera, role: String): List<CameraControl> {
    if (role != "admin") return emptyList()
    return CameraControl.entries.filter { control ->
        when (control) {
            CameraControl.CAMERA -> camera.enabledInConfig
            CameraControl.RECORDING -> camera.recordEnabledInConfig
            CameraControl.AUDIO_DETECTION -> camera.audioEnabledInConfig
            CameraControl.TRANSCRIPTION -> camera.transcriptionEnabledInConfig
            CameraControl.AUTOTRACKING -> camera.autotrackingEnabledInConfig
            else -> true
        }
    }
}

internal fun CameraActivity.controlValue(control: CameraControl, camera: FrigateCamera): Boolean =
    when (control) {
        CameraControl.CAMERA -> enabled
        CameraControl.DETECTION -> detectionEnabled
        CameraControl.RECORDING -> recordingEnabled
        else -> controls[control]
    } ?: control.configuredValue(camera)
