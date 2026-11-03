package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.FrigateWebSocketFrame
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A review segment update published on the `reviews` websocket topic. */
internal data class ReviewUpdate(
    val type: String,
    val afterSeverity: String?,
    val beforeObjectCount: Int,
    val afterObjectCount: Int,
) {
    val objectsGrew: Boolean get() = afterObjectCount > beforeObjectCount
}

/**
 * Review updates of the Live carousel, mirroring the refresh cadence of the PWA Live dashboard:
 * an ended alert is refetched after a second, a new or genai alert after six seconds and a
 * growing object list after five seconds.
 */
internal object ReviewUpdates {
    const val END_DELAY_MILLIS = 1_000L
    const val NEW_DELAY_MILLIS = 6_000L
    const val OBJECT_GROWTH_DELAY_MILLIS = 5_000L

    private const val REVIEWS_TOPIC = "reviews"
    private const val ALERT_SEVERITY = "alert"

    fun parseUpdate(raw: String): ReviewUpdate? {
        if (FrigateWebSocketFrame.topic(raw) != REVIEWS_TOPIC) return null
        val payload = FrigateWebSocketFrame.payload(raw) ?: return null
        val body = if (payload.isJsonPrimitive && payload.asJsonPrimitive.isString) {
            runCatching { JsonParser.parseString(payload.asString) }.getOrNull() ?: return null
        } else {
            payload
        }
        if (!body.isJsonObject) return null

        val update = body.asJsonObject
        val after = update.getAsJsonObject("after")
        val before = update.getAsJsonObject("before")
        return ReviewUpdate(
            type = update.get("type")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            afterSeverity = after?.get("severity")?.takeIf { !it.isJsonNull }?.asString,
            beforeObjectCount = objectCount(before),
            afterObjectCount = objectCount(after),
        )
    }

    fun refreshDelayMillis(raw: String): Long? = parseUpdate(raw)?.let(::delayFor)

    fun delayFor(update: ReviewUpdate): Long? {
        if (update.afterSeverity != ALERT_SEVERITY) return null
        return when (update.type) {
            "end" -> END_DELAY_MILLIS
            "new", "genai" -> NEW_DELAY_MILLIS
            else -> if (update.objectsGrew) OBJECT_GROWTH_DELAY_MILLIS else null
        }
    }

    private fun objectCount(segment: JsonObject?): Int =
        segment?.getAsJsonObject("data")?.getAsJsonArray("objects")?.size() ?: 0
}

/** Unreviewed alerts of the last hour, newest first, exactly like the Live dashboard of the PWA. */
internal fun unreviewedAlerts(
    items: List<FrigateReview>,
    cameraIds: Set<String>,
    nowSeconds: Double,
    limit: Int = 10,
): List<FrigateReview> = items
    .filter { it.severity == "alert" }
    .filter { !it.hasBeenReviewed }
    .filter { it.camera in cameraIds }
    .filter { it.startTime > nowSeconds - ROLLING_WINDOW_SECONDS }
    .distinctBy { it.id }
    .sortedByDescending { it.startTime }
    .take(limit)

private const val ROLLING_WINDOW_SECONDS = 3600.0
