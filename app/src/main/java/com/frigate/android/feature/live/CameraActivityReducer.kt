package com.frigate.android.feature.live

import com.google.gson.JsonParser

internal data class TrackedCameraObject(val label: String, val stationary: Boolean)

internal data class CameraRuntimeState(
    val activeMotion: Boolean = false,
    val recordingEnabled: Boolean? = null,
    val detectionEnabled: Boolean? = null,
    val enabled: Boolean? = null,
    val offline: Boolean? = null,
    val objectsById: Map<String, TrackedCameraObject> = emptyMap(),
    val unidentifiedSnapshotObjects: List<TrackedCameraObject> = emptyList(),
    val controls: Map<CameraControl, Boolean> = emptyMap(),
)

internal data class CameraActivityState(
    val runtimeByCamera: Map<String, CameraRuntimeState> = emptyMap(),
) {
    val cameras: Map<String, CameraActivity>
        get() = runtimeByCamera.mapValues { (_, runtime) ->
            val objects = runtime.objectsById.values + runtime.unidentifiedSnapshotObjects
            val trackedObjects = objects.asSequence()
                .filterNot(TrackedCameraObject::stationary)
                .map(TrackedCameraObject::label)
                .toSet()
            CameraActivity(
                activeMotion = runtime.enabled != false && runtime.activeMotion,
                activeTracking = runtime.enabled != false && trackedObjects.isNotEmpty(),
                trackedObjects = trackedObjects,
                displayedObjects = objects.map(TrackedCameraObject::label).toSet(),
                enabled = runtime.enabled,
                offline = runtime.offline,
                recordingEnabled = runtime.recordingEnabled,
                detectionEnabled = runtime.detectionEnabled,
                controls = runtime.controls,
            )
        }
}

internal object CameraActivityReducer {
    fun reduce(previous: CameraActivityState, envelope: String): CameraActivityState {
        val message = runCatching { JsonParser.parseString(envelope).asJsonObject }.getOrNull()
            ?: return previous
        val topic = message.get("topic")?.takeIf { it.isJsonPrimitive }?.asString ?: return previous
        val payload = message.get("payload")?.takeUnless { it.isJsonNull } ?: return previous
        val payloadValue = if ((topic == "camera_activity" || topic == "events" || topic == "stats") &&
            payload.isJsonPrimitive && payload.asJsonPrimitive.isString
        ) {
            runCatching { JsonParser.parseString(payload.asString) }.getOrNull() ?: payload
        } else {
            payload
        }

        return when {
            topic == "camera_activity" -> reduceSnapshot(previous, payloadValue)
            topic == "stats" -> reduceStats(previous, payloadValue)
            topic.endsWith("/enabled/state") -> {
                val camera = topic.removeSuffix("/enabled/state")
                val enabled = featureEnabled(payloadValue) ?: return previous
                if (camera.isBlank()) previous else previous.update(camera) { it.copy(enabled = enabled) }
            }
            topic == "events" -> reduceEvent(previous, payloadValue)
            topic.endsWith("/motion") -> reduceMotion(previous, topic.removeSuffix("/motion"), payloadValue)
            topic.endsWith("/recordings/state") -> reduceFeatureState(
                previous,
                topic.removeSuffix("/recordings/state"),
                payloadValue,
                recording = true,
            )
            topic.endsWith("/detect/state") -> reduceFeatureState(
                previous,
                topic.removeSuffix("/detect/state"),
                payloadValue,
                recording = false,
            )
            topic.endsWith("/state") -> {
                val control = CameraControl.entries.firstOrNull { topic.endsWith("/${it.topic}/state") }
                    ?: return previous
                val camera = topic.removeSuffix("/${control.topic}/state")
                val enabled = featureEnabled(payloadValue) ?: return previous
                if (camera.isBlank()) previous else previous.update(camera) {
                    it.copy(controls = it.controls + (control to enabled))
                }
            }
            else -> previous
        }
    }

    private fun reduceSnapshot(previous: CameraActivityState, payload: com.google.gson.JsonElement): CameraActivityState {
        if (!payload.isJsonObject) return previous
        val snapshot = CameraActivityParser.parseSnapshot(payload.toString()).getOrNull() ?: return previous
        return previous.copy(runtimeByCamera = previous.runtimeByCamera + snapshot.runtimeByCamera.mapValues { (camera, runtime) ->
            runtime.copy(offline = previous.runtimeByCamera[camera]?.offline)
        })
    }

    private fun featureEnabled(payload: com.google.gson.JsonElement): Boolean? {
        if (!payload.isJsonPrimitive) return null
        return when (payload.asString.lowercase()) {
            "true", "on" -> true
            "false", "off" -> false
            else -> null
        }
    }

    private fun reduceStats(previous: CameraActivityState, payload: com.google.gson.JsonElement): CameraActivityState {
        return runCatching {
            val root = payload.asJsonObject
            val uptime = root.getAsJsonObject("service")?.get("uptime")?.asDouble ?: return previous
            if (!uptime.isFinite() || uptime < 0) return previous
            val cameras = root.getAsJsonObject("cameras") ?: return previous
            var next = previous
            cameras.entrySet().forEach { (camera, value) ->
                val fps = runCatching { value.asJsonObject.get("camera_fps")?.asDouble }.getOrNull()
                if (fps != null && fps.isFinite() && fps >= 0) {
                    next = next.update(camera) { it.copy(offline = uptime > 60 && fps == 0.0) }
                }
            }
            next
        }.getOrDefault(previous)
    }

    private fun reduceMotion(
        previous: CameraActivityState,
        camera: String,
        payload: com.google.gson.JsonElement,
    ): CameraActivityState {
        if (camera.isBlank() || !payload.isJsonPrimitive) return previous
        val active = when {
            payload.asJsonPrimitive.isBoolean -> payload.asBoolean
            else -> payload.asString.equals("ON", ignoreCase = true)
        }
        return previous.update(camera) { it.copy(activeMotion = active) }
    }

    private fun reduceFeatureState(
        previous: CameraActivityState,
        camera: String,
        payload: com.google.gson.JsonElement,
        recording: Boolean,
    ): CameraActivityState {
        if (camera.isBlank() || !payload.isJsonPrimitive) return previous
        val enabled = when {
            payload.asJsonPrimitive.isBoolean -> payload.asBoolean
            else -> payload.asString.equals("ON", ignoreCase = true) ||
                payload.asString.equals("true", ignoreCase = true)
        }
        return previous.update(camera) {
            if (recording) it.copy(recordingEnabled = enabled) else it.copy(detectionEnabled = enabled)
        }
    }

    private fun reduceEvent(
        previous: CameraActivityState,
        payload: com.google.gson.JsonElement,
    ): CameraActivityState {
        if (!payload.isJsonObject) return previous
        val event = payload.asJsonObject
        val after = event.get("after")?.takeIf { it.isJsonObject }?.asJsonObject ?: return previous
        val camera = after.get("camera")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
            ?: return previous
        val id = after.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
            ?: return previous
        val type = event.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return previous
        if (type !in setOf("new", "start", "update", "end")) return previous
        val current = previous.runtimeByCamera[camera] ?: CameraRuntimeState()
        val existing = current.objectsById[id]
        if (type == "end") {
            if (existing == null) return previous
            return previous.update(camera) { runtime ->
                runtime.copy(objectsById = runtime.objectsById - id)
            }
        }

        val stationary = after.get("stationary")?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: existing?.stationary
            ?: false
        val label = after.get("label")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
            ?: existing?.label
            ?: "object"
        return previous.update(camera) { runtime ->
            runtime.copy(objectsById = runtime.objectsById + (id to TrackedCameraObject(label, stationary)))
        }
    }

    private fun CameraActivityState.update(
        camera: String,
        transform: (CameraRuntimeState) -> CameraRuntimeState,
    ): CameraActivityState {
        val updated = transform(runtimeByCamera[camera] ?: CameraRuntimeState())
        return copy(runtimeByCamera = runtimeByCamera + (camera to updated))
    }
}
