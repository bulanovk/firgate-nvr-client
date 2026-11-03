package com.frigate.android.feature.live

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class CameraActivity(
    val activeMotion: Boolean = false,
    val activeTracking: Boolean = false,
    val trackedObjects: Set<String> = emptySet(),
    val recordingEnabled: Boolean? = null,
    val detectionEnabled: Boolean? = null,
    val enabled: Boolean? = null,
    val offline: Boolean? = null,
    val displayedObjects: Set<String> = trackedObjects,
    val controls: Map<CameraControl, Boolean> = emptyMap(),
) {
    val activeRecording: Boolean
        get() = activeMotion || activeTracking
}

internal object CameraActivityParser {
    fun parse(payload: String): Map<String, CameraActivity> =
        parseSnapshot(payload).getOrNull()?.cameras.orEmpty()

    fun parseSnapshot(payload: String): Result<CameraActivityState> = runCatching {
        val root = JsonParser.parseString(payload).asJsonObject
        val runtime = root.entrySet().associate { (camera, value) ->
            camera to parseRuntime(value.asJsonObject)
        }
        CameraActivityState(runtime)
    }

    private fun parseRuntime(state: JsonObject): CameraRuntimeState {
        val objects = state.getAsJsonArray("objects")
            ?.mapNotNull { objectValue ->
                if (!objectValue.isJsonObject) return@mapNotNull null
                val objectState = objectValue.asJsonObject
                val tracked = TrackedCameraObject(
                    label = objectState.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "object",
                    stationary = objectState.booleanOrDefault("stationary", false),
                )
                val id = objectState.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
                id?.let { it to tracked } ?: (null to tracked)
            }
            .orEmpty()
        val config = state.getAsJsonObject("config")
        return CameraRuntimeState(
            activeMotion = state.booleanOrDefault("motion", false),
            enabled = config?.booleanOrNull("enabled"),
            recordingEnabled = config?.booleanOrNull("record"),
            detectionEnabled = config?.booleanOrNull("detect"),
            controls = CameraControl.entries.mapNotNull { control ->
                config?.booleanOrNull(control.snapshotKey)?.let { control to it }
            }.toMap(),
            objectsById = objects.mapNotNull { (id, tracked) -> id?.let { it to tracked } }.toMap(),
            unidentifiedSnapshotObjects = objects.filter { it.first == null }.map { it.second },
        )
    }

    private fun JsonObject.booleanOrDefault(name: String, fallback: Boolean): Boolean =
        if (has(name) && !get(name).isJsonNull) get(name).asBoolean else fallback

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        if (has(name) && !get(name).isJsonNull) get(name).asBoolean else null
}
