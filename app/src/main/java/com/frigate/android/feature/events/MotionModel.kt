package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import kotlin.math.abs
import kotlin.math.floor

internal val MOTION_SPEEDS = listOf(4, 8, 12, 16)
internal const val MOTION_DEFAULT_SPEED = 8

private fun MotionPoint.includes(camera: String) = this.camera.split(',').any { it.trim() == camera }
private fun FrigateReview.containsObject() = severity in setOf("alert", "detection") || detections.isNotEmpty() || objectLabels.isNotEmpty()
private fun FrigateReview.overlaps(start: Double, end: Double, now: Double) = startTime < end && (endTime ?: now) > start

internal fun motionOnlyRanges(points: List<MotionPoint>, reviews: List<FrigateReview>, cameraId: String, now: Double): List<EventsRange> {
    val objects = reviews.filter { it.camera == cameraId && it.containsObject() }
    val buckets = points.filter { it.includes(cameraId) && it.startTime.isFinite() && it.motion.isFinite() }
        .groupBy { it.startTime }.toSortedMap().mapNotNull { (start, items) ->
            val end = start + 15
            if (items.maxOf { it.motion } <= 0 || objects.any { it.overlaps(start, end, now) }) null else EventsRange(start, end, null)
        }
    val merged = mutableListOf<EventsRange>()
    buckets.forEach { bucket ->
        val previous = merged.lastOrNull()
        if (previous != null && abs(previous.before - bucket.after) <= .001) {
            merged[merged.lastIndex] = previous.copy(before = maxOf(previous.before, bucket.before))
        } else merged += bucket
    }
    return merged
}

internal fun nextMotionTimestamp(current: Double, query: EventsQuery, points: List<MotionPoint>, reviews: List<FrigateReview>, motionOnly: Boolean, now: Double): Double? {
    val next = maxOf(current + .5, query.range.after)
    if (next >= query.range.before) return null
    if (!motionOnly) return next
    val cameras = query.cameraIds.toSet()
    val objects = reviews.filter { it.camera in cameras && it.containsObject() }
    val segments = points.filter { it.motion > 0 && it.motion.isFinite() && it.startTime.isFinite() && cameras.any(it::includes) }
        .map { floor(it.startTime / 30) * 30 }.distinct().sorted()
        .filter { start -> start < query.range.before && start + 30 > query.range.after && objects.none { it.overlaps(start, start + 30, now) } }
    for (start in segments) {
        val candidate = maxOf(next, start, query.range.after)
        if (candidate < minOf(start + 30, query.range.before)) return candidate
    }
    return null
}
