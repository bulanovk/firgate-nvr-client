package com.frigate.android.feature.events

import kotlin.math.floor

internal fun selectPreview(previews: List<ReviewPreview>, cameraId: String, range: EventsRange): ReviewPreview? =
    previews.filter { it.cameraId == cameraId && it.endTime > range.after && it.startTime < range.before }
        .maxByOrNull { minOf(it.endTime, range.before) - maxOf(it.startTime, range.after) }

internal fun previewOffset(preview: ReviewPreview, wallClock: Double): Double? =
    if (wallClock.isFinite() && wallClock >= preview.startTime && wallClock < preview.endTime) wallClock - preview.startTime else null

internal fun previewFrameCandidates(frames: List<PreviewFrame>, timestamp: Double): List<PreviewFrame> {
    val sorted = frames.sortedBy { it.timestamp }
    return sorted.filter { it.timestamp >= timestamp }.take(3).ifEmpty { sorted.takeLast(1) }
}

internal fun usesPreviewFrames(timestamp: Double, now: Double): Boolean = floor(timestamp / 3600) == floor(now / 3600)

/** Decoder ownership is explicit; retaining an empty viewport releases every source immediately. */
internal class EventsPreviewPool<T>(private val capacity: Int, private val release: (T) -> Unit) {
    init { require(capacity > 0) }
    private val players = linkedMapOf<String, T>()
    fun acquire(key: String, create: () -> T): T? = players[key] ?: if (players.size < capacity) create().also { players[key] = it } else null
    fun retain(keys: Set<String>) {
        val removed = players.keys.filterNot { it in keys }
        removed.forEach { players.remove(it)?.let(release) }
    }
    fun close() = retain(emptySet())
}
