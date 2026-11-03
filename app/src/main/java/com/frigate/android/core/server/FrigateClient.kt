package com.frigate.android.core.server

import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

/** The biggest answer the app reads from the server. */
internal const val MAX_RESPONSE_BODY_CHARS = 16 * 1024 * 1024

class FrigateClient(
    val server: ServerProfile,
    sessionCookie: String? = null,
) {
    private var cookie: String? = sessionCookie

    fun apiUrl(path: String): String {
        val base = server.baseUrl.trimEnd('/')
        val normalizedPath = path.trimStart('/')
        return "$base/api/$normalizedPath"
    }

    fun cookieHeader(): String = cookie.orEmpty()

    fun acceptSetCookie(setCookie: String) {
        cookie = setCookie.substringBefore(';')
    }

    fun login(user: String, password: String): FrigateProfile {
        val response = request(
            method = "POST",
            path = "login",
            body = "{\"user\":\"${escapeJson(user)}\",\"password\":\"${escapeJson(password)}\"}",
        )
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Login failed")
        response.setCookie?.let(::acceptSetCookie)
        return profile()
    }

    fun profile(): FrigateProfile {
        val response = request("GET", "profile")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Profile request failed")
        return FrigateProfileParser.parse(response.body)
    }

    fun cameras(): List<FrigateCamera> = configuration().cameras

    fun configuration(): FrigateConfiguration {
        val response = request("GET", "config")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Config request failed")
        return FrigateApiParser.parseConfiguration(response.body)
    }

    fun stats(): String {
        val response = request("GET", "stats")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Stats request failed")
        return response.body
    }

    fun events(limit: Int = 50): List<FrigateEvent> {
        val response = request("GET", "events?limit=$limit")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Events request failed")
        return FrigateApiParser.parseEvents(response.body)
    }

    fun liveStreamInfo(stream: String): LiveStreamInfo {
        val response = request("GET", "go2rtc/streams/${UrlComponents.encodeComponent(stream)}")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Stream information request failed")
        val root = com.google.gson.JsonParser.parseString(response.body).asJsonObject
        val medias = root.getAsJsonArray("producers")?.flatMap { producer ->
            producer.asJsonObject.getAsJsonArray("medias")?.map { it.asString }.orEmpty()
        }.orEmpty().map { media -> media.split(',').map(String::trim) }
        return LiveStreamInfo(
            audioOutput = medias.any { it.getOrNull(0) == "audio" && it.getOrNull(1) == "recvonly" },
            twoWayAudio = medias.any { it.getOrNull(0) == "audio" && it.getOrNull(1) == "sendonly" },
        )
    }

    fun startManualRecording(camera: String): String {
        val response = request("POST", "events/${UrlComponents.encodeComponent(camera)}/on_demand/create",
            "{\"include_recording\":true,\"duration\":null}")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Could not start recording")
        val root = com.google.gson.JsonParser.parseString(response.body).asJsonObject
        val eventId = root.get("event_id")?.takeUnless { it.isJsonNull }?.asString
        if (root.get("success")?.asBoolean != true || eventId.isNullOrBlank()) {
            throw IOException("Server did not start a recording")
        }
        return eventId
    }

    fun endManualRecording(eventId: String, endTime: Long = kotlin.math.ceil(System.currentTimeMillis() / 1000.0).toLong()) {
        val response = request("PUT", "events/${UrlComponents.encodeComponent(eventId)}/end", "{\"end_time\":$endTime}")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Could not stop recording")
        val root = com.google.gson.JsonParser.parseString(response.body).asJsonObject
        if (root.get("success")?.asBoolean != true) throw IOException("Server did not stop the recording")
    }

    fun mediaUrl(path: String): String = "${server.baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    /**
     * Review segments of the given cameras. The server filters by `severity`, `reviewed`, `limit`
     * and the `before`/`after` window; an empty camera list never reaches the network.
     */
    fun reviews(
        cameraIds: List<String>,
        severity: String? = null,
        reviewed: Int? = null,
        limit: Int? = null,
        before: Double? = null,
        after: Double? = null,
        labels: List<String> = emptyList(),
        zones: List<String> = emptyList(),
    ): List<FrigateReview> {
        val query = reviewQuery(cameraIds, severity, reviewed, limit, before, after, labels, zones)
        if (query.isEmpty()) return emptyList()

        val response = request("GET", "review?$query")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Review request failed")
        return FrigateApiParser.parseReviews(response.body)
    }

    fun reviewQuery(
        cameraIds: List<String>,
        severity: String? = null,
        reviewed: Int? = null,
        limit: Int? = null,
        before: Double? = null,
        after: Double? = null,
        labels: List<String> = emptyList(),
        zones: List<String> = emptyList(),
    ): String {
        if (cameraIds.isEmpty()) return ""

        val parameters = buildList {
            add("cameras" to UrlComponents.encodeComponent(cameraIds.joinToString(",")))
            if (labels.isNotEmpty()) add("labels" to UrlComponents.encodeComponent(labels.joinToString(",")))
            if (zones.isNotEmpty()) add("zones" to UrlComponents.encodeComponent(zones.joinToString(",")))
            severity?.let { add("severity" to it) }
            reviewed?.let { add("reviewed" to it.toString()) }
            limit?.let { add("limit" to it.toString()) }
            before?.let { add("before" to UrlComponents.formatTimestamp(it)) }
            after?.let { add("after" to UrlComponents.formatTimestamp(it)) }
        }
        return parameters.joinToString("&") { "${it.first}=${it.second}" }
    }

    fun reviewSummaryQuery(
        cameraIds: List<String>,
        timezone: String,
        labels: List<String> = emptyList(),
        zones: List<String> = emptyList(),
    ): String {
        val filters = reviewQuery(cameraIds = cameraIds, labels = labels, zones = zones)
        if (filters.isEmpty()) return ""
        return "$filters&timezone=${UrlComponents.encodeComponent(timezone)}"
    }

    fun reviewSummary(
        cameraIds: List<String>,
        timezone: String,
        labels: List<String> = emptyList(),
        zones: List<String> = emptyList(),
    ): FrigateReviewSummary {
        val query = reviewSummaryQuery(cameraIds, timezone, labels, zones)
        if (query.isEmpty()) return FrigateReviewSummary(null, emptyMap())
        val response = request("GET", "review/summary?$query")
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Review summary request failed")
        return FrigateApiParser.parseReviewSummary(response.body)
    }

    fun markReviewsViewed(ids: List<String>, reviewed: Boolean = true) {
        if (ids.isEmpty()) return

        val body = buildString {
            append("{\"ids\":[")
            append(ids.joinToString(",") { "\"${escapeJson(it)}\"" })
            append("],\"reviewed\":").append(reviewed).append("}")
        }
        val response = request("POST", "reviews/viewed", body)
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Review update failed")
    }

    /** Motion activity of one camera, resampled by the server to `scale` seconds. */
    fun reviewMotion(camera: String, before: Double, after: Double, scale: Int): List<MotionPoint> {
        val query = "review/activity/motion?cameras=${UrlComponents.encodeComponent(camera)}" +
            "&before=${UrlComponents.formatTimestamp(before)}" +
            "&after=${UrlComponents.formatTimestamp(after)}" +
            "&scale=$scale"
        val response = request("GET", query)
        if (response.code !in 200..299) {
            throw FrigateHttpException(response.code, "Motion activity request failed")
        }
        return FrigateApiParser.parseMotionActivity(response.body)
    }

    /** Windows of the given camera without any recording. */
    fun unavailableRecordings(camera: String, before: Double, after: Double, scale: Int): List<UnavailableSpan> {
        val query = "recordings/unavailable?cameras=${UrlComponents.encodeComponent(camera)}" +
            "&before=${UrlComponents.formatTimestamp(before)}" +
            "&after=${UrlComponents.formatTimestamp(after)}" +
            "&scale=$scale"
        val response = request("GET", query)
        if (response.code !in 200..299) {
            throw FrigateHttpException(response.code, "Unavailable recordings request failed")
        }
        return FrigateApiParser.parseUnavailableRecordings(response.body, camera)
    }

    /** Recording coverage of one camera, with the realized playlist timelines when asked for. */
    fun recordingCoverage(
        camera: String,
        before: Double,
        after: Double,
        timelines: Boolean = true,
    ): RecordingCoverage {
        val query = "${UrlComponents.encodeComponent(camera)}/recordings/coverage" +
            "?before=${UrlComponents.formatTimestamp(before)}" +
            "&after=${UrlComponents.formatTimestamp(after)}" +
            "&timelines=$timelines"
        val response = request("GET", query)
        if (response.code !in 200..299) {
            throw FrigateHttpException(response.code, "Recording coverage request failed")
        }
        return FrigateApiParser.parseRecordingCoverage(response.body)
    }

    internal fun apiJson(method: String, path: String, body: com.google.gson.JsonObject? = null): com.google.gson.JsonElement {
        require(!path.startsWith('/') && !path.contains("://") && !path.contains(".."))
        val response = request(method, path, body?.toString())
        if (response.code !in 200..299) throw FrigateHttpException(response.code, "Server request failed")
        val result = com.google.gson.JsonParser.parseString(response.body)
        if (result.isJsonObject && result.asJsonObject.get("success")?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean
        } == true) throw IOException("Server rejected the operation")
        return result
    }

    private fun request(method: String, path: String, body: String? = null): HttpResponse {
        val connection = (URL(apiUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            cookieHeader().takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-CSRF-TOKEN", "1")
            }
        }

        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val responseBody = (if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { readBoundedBody(it) }
                .orEmpty()
            HttpResponse(connection.responseCode, responseBody, connection.getHeaderField("Set-Cookie"))
        } catch (error: IOException) {
            throw FrigateNetworkException(error.message ?: "Network request failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private data class HttpResponse(val code: Int, val body: String, val setCookie: String?)
}

data class FrigateProfile(
    val username: String,
    val role: String,
    val allowedCameras: Set<String>?,
)

/**
 * Reads the body of an answer, but not more than [limit] characters.
 *
 * A bigger answer is a failure of the server and not a body that is cut in the middle: a cut body
 * would be parsed as a broken answer and would hide the real reason.
 */
internal fun readBoundedBody(reader: Reader, limit: Int = MAX_RESPONSE_BODY_CHARS): String {
    val builder = StringBuilder()
    val chunk = CharArray(8 * 1024)
    while (true) {
        val read = reader.read(chunk)
        if (read < 0) break
        builder.append(chunk, 0, read)
        if (builder.length > limit) throw IOException("The answer of the server is too large")
    }
    return builder.toString()
}

class FrigateHttpException(val code: Int, message: String) : IOException(message)
class FrigateNetworkException(message: String, cause: Throwable) : IOException(message, cause)
