package com.frigate.android.core.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object FrigateApiParser {
    fun parseConfiguration(body: String): FrigateConfiguration {
        val root = JsonParser.parseString(body).asJsonObject
        val timezone = root.objectOrNull("ui")?.optionalString("timezone")?.takeIf {
            runCatching { java.time.ZoneId.of(it) }.isSuccess
        }
        return FrigateConfiguration(parseCameras(root), timezone)
    }

    fun parseCameras(body: String): List<FrigateCamera> =
        parseCameras(JsonParser.parseString(body).asJsonObject)

    private fun parseCameras(root: JsonObject): List<FrigateCamera> {
        val cameras = root.getAsJsonObject("cameras")
        return cameras.entrySet()
            .map { (id, value) ->
                val camera = value.asJsonObject
                val streams = camera.objectOrNull("live")?.objectOrNull("streams")
                    ?.entrySet()?.mapNotNull { (label, stream) ->
                        if (stream.isJsonPrimitive && stream.asJsonPrimitive.isString && stream.asString.isNotBlank()) {
                            label to stream.asString
                        } else null
                    }?.toMap()?.takeIf { it.isNotEmpty() } ?: mapOf(id to id)
                val streamName = streams.values.first()
                val record = camera.objectOrNull("record")
                val audio = camera.objectOrNull("audio")
                val transcription = camera.objectOrNull("audio_transcription")
                val onvif = camera.objectOrNull("onvif")
                val autotracking = onvif?.objectOrNull("autotracking")
                val review = camera.objectOrNull("review")
                val labels = camera.objectOrNull("objects")?.stringList("track").orEmpty() +
                    (if (camera.optionalString("type") == "lpr") listOf("license_plate") else emptyList()) +
                    (if (audio?.booleanOrDefault("enabled_in_config", audio.booleanOrDefault("enabled", false)) == true)
                        audio.stringList("listen") else emptyList())
                val zones = review?.objectOrNull("alerts")?.stringList("required_zones").orEmpty() +
                    review?.objectOrNull("detections")?.stringList("required_zones").orEmpty()
                FrigateCamera(
                    id = id,
                    name = camera.stringOrDefault("friendly_name", id),
                    enabled = camera.booleanOrDefault("enabled", true),
                    streamName = streamName,
                    recordEnabled = camera.getAsJsonObject("record")
                        ?.booleanOrDefault("enabled", false)
                        ?: false,
                    detectWidth = camera.getAsJsonObject("detect")?.get("width")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    detectHeight = camera.getAsJsonObject("detect")?.get("height")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    streams = streams,
                    enabledInConfig = camera.booleanOrDefault("enabled_in_config", camera.booleanOrDefault("enabled", true)),
                    recordEnabledInConfig = record?.booleanOrDefault("enabled_in_config", record.booleanOrDefault("enabled", false)) ?: false,
                    snapshotsEnabled = camera.objectOrNull("snapshots")?.booleanOrDefault("enabled", false) ?: false,
                    audioEnabled = audio?.booleanOrDefault("enabled", false) ?: false,
                    audioEnabledInConfig = audio?.booleanOrDefault("enabled_in_config", audio.booleanOrDefault("enabled", false)) ?: false,
                    transcriptionEnabled = transcription?.booleanOrDefault("live_enabled", false) ?: false,
                    transcriptionEnabledInConfig = transcription?.booleanOrDefault("enabled_in_config", transcription.booleanOrDefault("enabled", false)) ?: false,
                    autotrackingEnabled = autotracking?.booleanOrDefault("enabled", false) ?: false,
                    autotrackingEnabledInConfig = autotracking?.booleanOrDefault("enabled_in_config", autotracking.booleanOrDefault("enabled", false)) ?: false,
                    hasOnvif = !onvif?.optionalString("host").isNullOrBlank(),
                    reviewLabels = labels.distinct().sorted(),
                    reviewZones = zones.distinct().sorted(),
                    detectEnabled = camera.getAsJsonObject("detect")
                        ?.booleanOrDefault("enabled", true)
                        ?: true,
                )
            }
            .sortedBy(FrigateCamera::name)
    }

    fun parseEvents(body: String): List<FrigateEvent> = JsonParser.parseString(body).asJsonArray.map { value ->
        val event = value.asJsonObject
        FrigateEvent(
            id = event.stringOrDefault("id", ""),
            camera = event.stringOrDefault("camera", ""),
            label = event.stringOrDefault("label", "unknown"),
            startTime = event.doubleOrDefault("start_time", 0.0),
            endTime = event.optionalDouble("end_time"),
            hasClip = event.booleanOrDefault("has_clip", false),
            hasSnapshot = event.booleanOrDefault("has_snapshot", false),
        )
    }

    fun parseReviews(body: String): List<FrigateReview> = JsonParser.parseString(body).asJsonArray.mapNotNull { value ->
        val review = value.asJsonObject
        val id = review.stringOrDefault("id", "")
        val camera = review.stringOrDefault("camera", "")
        if (id.isBlank() || camera.isBlank()) return@mapNotNull null
        if (!review.has("start_time") || review.get("start_time").isJsonNull) return@mapNotNull null

        val data = review.objectOrNull("data")
        val metadata = data?.objectOrNull("metadata")
        FrigateReview(
            id = id,
            camera = camera,
            startTime = review.doubleOrDefault("start_time", 0.0),
            endTime = review.optionalDouble("end_time"),
            severity = review.stringOrDefault("severity", "detection"),
            hasBeenReviewed = review.booleanOrDefault("has_been_reviewed", false),
            thumbnailPath = review.optionalString("thumb_path"),
            objectLabels = data?.stringList("objects").orEmpty(),
            zones = data?.stringList("zones").orEmpty(),
            audioLabels = data?.stringList("audio").orEmpty(),
            subLabels = data?.stringList("sub_labels").orEmpty(),
            detections = data?.stringList("detections").orEmpty(),
            threatLevel = metadata?.get("potential_threat_level")?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber
            }?.asInt,
            title = metadata?.get("title")?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }?.asString?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun parseReviewSummary(body: String): FrigateReviewSummary {
        val root = JsonParser.parseString(body).asJsonObject
        fun counts(value: JsonObject) = FrigateReviewCounts(
            totalAlert = value.doubleOrDefault("total_alert", 0.0).toInt(),
            reviewedAlert = value.doubleOrDefault("reviewed_alert", 0.0).toInt(),
            totalDetection = value.doubleOrDefault("total_detection", 0.0).toInt(),
            reviewedDetection = value.doubleOrDefault("reviewed_detection", 0.0).toInt(),
        )
        return FrigateReviewSummary(
            last24Hours = root.objectOrNull("last24Hours")?.let(::counts),
            days = root.entrySet().mapNotNull { (key, value) ->
                if (key.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && value.isJsonObject) {
                    key to counts(value.asJsonObject)
                } else null
            }.toMap(),
        )
    }

    /** Motion activity of one camera, resampled by the server to the requested scale. */
    fun parseMotionActivity(body: String): List<MotionPoint> =
        JsonParser.parseString(body).asJsonArray.mapNotNull { value ->
            val point = value.asJsonObject
            val startTime = point.optionalDouble("start_time") ?: return@mapNotNull null
            MotionPoint(
                startTime = startTime,
                motion = point.doubleOrDefault("motion", 0.0),
                camera = point.stringOrDefault("camera", ""),
            )
        }

    /** Windows without any recording. The server does not name the camera in the payload. */
    fun parseUnavailableRecordings(body: String, camera: String): List<UnavailableSpan> =
        JsonParser.parseString(body).asJsonArray.mapNotNull { value ->
            val span = value.asJsonObject
            val startTime = span.optionalDouble("start_time") ?: return@mapNotNull null
            val endTime = span.optionalDouble("end_time") ?: return@mapNotNull null
            UnavailableSpan(camera = camera, startTime = startTime, endTime = endTime)
        }

    /** Recording coverage of one camera including the realized playlist timelines. */
    fun parseRecordingCoverage(body: String): RecordingCoverage {
        val root = JsonParser.parseString(body).asJsonObject
        val spans = root.arrayOrNull("spans")?.mapNotNull { value ->
            val span = value.asJsonObject
            val startTime = span.optionalDouble("start_time") ?: return@mapNotNull null
            val endTime = span.optionalDouble("end_time") ?: return@mapNotNull null
            CoverageSpan(startTime = startTime, endTime = endTime, streams = span.stringList("streams"))
        }.orEmpty()
        val timelines = root.objectOrNull("timelines")?.entrySet()?.associate { (name, value) ->
            name to value.asJsonArray.mapNotNull { element ->
                val span = element.asJsonObject
                val startTime = span.optionalDouble("start_time") ?: return@mapNotNull null
                val endTime = span.optionalDouble("end_time") ?: return@mapNotNull null
                TimelineSpan(
                    startTime = startTime,
                    endTime = endTime,
                    durationMs = span.doubleOrDefault("duration", 0.0).toInt(),
                )
            }
        }.orEmpty()
        return RecordingCoverage(spans = spans, timelines = timelines)
    }

    private fun JsonObject.stringOrDefault(name: String, fallback: String): String =
        if (has(name) && !get(name).isJsonNull) get(name).asString else fallback

    private fun JsonObject.optionalString(name: String): String? =
        if (has(name) && !get(name).isJsonNull) get(name).asString else null

    private fun JsonObject.stringList(name: String): List<String> {
        val array = get(name) ?: return emptyList()
        if (!array.isJsonArray) return emptyList()
        return array.asJsonArray.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
        }
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val member = get(name) ?: return null
        if (!member.isJsonObject) return null
        return member.asJsonObject
    }

    private fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val member = get(name) ?: return null
        if (!member.isJsonArray) return null
        return member.asJsonArray
    }

    private fun JsonObject.booleanOrDefault(name: String, fallback: Boolean): Boolean =
        if (has(name) && !get(name).isJsonNull) get(name).asBoolean else fallback

    private fun JsonObject.doubleOrDefault(name: String, fallback: Double): Double =
        if (has(name) && !get(name).isJsonNull) get(name).asDouble else fallback

    private fun JsonObject.optionalDouble(name: String): Double? =
        if (has(name) && !get(name).isJsonNull) get(name).asDouble else null
}
