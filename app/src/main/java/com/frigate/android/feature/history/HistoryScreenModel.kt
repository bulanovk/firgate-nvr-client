package com.frigate.android.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The state of the History screen that the player does not own.
 *
 * The playhead is kept in wall-clock time: the anchor is the moment of the day the user is looking
 * at, so a camera change keeps the moment and only the recorded content changes. The seek counter
 * tells the player that the playhead moved inside the playlist that is already open.
 */
internal class HistoryScreenModel(
    anchorSeconds: Double,
    bounds: ClosedFloatingPointRange<Double>,
) {
    var anchorSeconds: Double by mutableStateOf(anchorSeconds.coerceIn(bounds.start, bounds.endInclusive))
        private set

    var zoomIndex: Int by mutableIntStateOf(0)
        private set

    /** Bumped on every explicit seek so the player moves inside the playlist it already opened. */
    var seekToken: Int by mutableIntStateOf(0)
        private set

    var bounds: ClosedFloatingPointRange<Double> = bounds
        set(value) {
            field = value
            anchorSeconds = anchorSeconds.coerceIn(value.start, value.endInclusive)
        }

    /** The zoom of the timeline: the segment length and the spread of the timestamps. */
    val zoom: TimelineZoom get() = HISTORY_ZOOM_LEVELS[zoomIndex]

    fun seekTo(seconds: Double) {
        anchorSeconds = seconds.coerceIn(bounds.start, bounds.endInclusive)
        seekToken += 1
    }

    fun zoomIn() {
        if (zoomIndex < HISTORY_ZOOM_LEVELS.lastIndex) zoomIndex += 1
    }

    fun zoomOut() {
        if (zoomIndex > 0) zoomIndex -= 1
    }
}
