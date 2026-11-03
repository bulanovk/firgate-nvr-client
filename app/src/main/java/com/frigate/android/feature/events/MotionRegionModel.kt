package com.frigate.android.feature.events

import kotlin.math.sqrt

internal data class NormalizedRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

internal fun mergedHeatmap(recordings: List<MotionRecording>, range: EventsRange): Map<Int, Double> {
    val sums = mutableMapOf<Int, Double>()
    var duration = 0.0
    recordings.forEach { recording ->
        val overlap = (minOf(recording.endTime, range.before) - maxOf(recording.startTime, range.after)).coerceAtLeast(0.0)
        duration += overlap
        if (overlap > 0) recording.heatmap.forEach { (cell, value) ->
            if (cell in 0..255) sums[cell] = (sums[cell] ?: 0.0) + value.coerceIn(0, 255) * overlap
        }
    }
    return if (duration <= 0) emptyMap() else sums.mapValues { it.value / duration }
}

internal fun matchesRegion(heatmap: Map<Int, Double>, cells: Set<Int>): Boolean = cells.isEmpty() || cells.any { (heatmap[it] ?: 0.0) > 0 }

internal fun regionBounds(cells: Set<Int>): NormalizedRect? {
    val valid = cells.filter { it in 0..255 }
    if (valid.isEmpty()) return null
    return NormalizedRect(valid.minOf { it % 16 } / 16.0, valid.minOf { it / 16 } / 16.0,
        (valid.maxOf { it % 16 } + 1) / 16.0, (valid.maxOf { it / 16 } + 1) / 16.0)
}

internal fun regionAspect(rect: NormalizedRect, sourceAspect: Double): Double =
    (sourceAspect * (rect.right - rect.left) / (rect.bottom - rect.top)).coerceIn(.75, 4.0)

internal fun motionDimAlpha(value: Double, maximum: Double, dimPercent: Int): Double {
    val dim = dimPercent.coerceIn(0, 100) / 100.0
    val normalized = if (maximum > 0) sqrt((value / maximum).coerceIn(0.0, 1.0)) else 0.0
    return dim * (1 - normalized) + .06 * normalized
}

internal class MotionRegionSelection(cameraId: String) {
    private var camera = cameraId
    var applied: Set<Int> = emptySet()
        private set
    var pending: Set<Int> = emptySet()
        private set
    var crop = true
    var dimPercent = 82
    fun toggle(cell: Int) {
        require(cell in 0..255)
        pending = if (cell in pending) pending - cell else pending + cell
    }
    fun apply() { applied = pending.toSet() }
    fun cancel() { pending = applied.toSet() }
    fun clear() { pending = emptySet() }
    fun changeCamera(cameraId: String) {
        if (cameraId == camera) return
        camera = cameraId
        applied = emptySet(); pending = emptySet()
    }
}
