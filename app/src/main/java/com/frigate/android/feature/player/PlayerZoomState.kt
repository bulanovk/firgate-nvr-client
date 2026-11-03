package com.frigate.android.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

private const val PLAYER_DOUBLE_TAP_TIMEOUT_MILLIS = 300L

/**
 * The zoom of a player: the scale of the picture and its shift in frame pixels.
 *
 * The limit of the zoom comes from the resolution of the source, so a 4K stream can be enlarged
 * ten times and a 360p stream cannot be enlarged at all.
 */
@Stable
internal class PlayerZoomState {
    var zoom by mutableStateOf(PlayerZoom())
        private set

    /** The width of the decoded frame in pixels; zero until the first frame is decoded. */
    var sourceWidthPx by mutableIntStateOf(0)
        private set

    private var pictureWidthPx by mutableFloatStateOf(0f)
    private var pictureHeightPx by mutableFloatStateOf(0f)

    val maxScale: Float get() = playerZoomMaxScale(sourceWidthPx, pictureWidthPx)

    fun onPictureSize(widthPx: Int, heightPx: Int) {
        pictureWidthPx = widthPx.toFloat()
        pictureHeightPx = heightPx.toFloat()
    }

    fun onSourceSize(widthPx: Int) {
        sourceWidthPx = widthPx
    }

    /** One step of a gesture: the scale grows around the focus, the picture follows the fingers. */
    fun onGesture(centroid: Offset, pan: Offset, zoomChange: Float) {
        var next = zoom
        if (zoomChange != 1f) {
            next = zoomAround(next, zoomChange, centroid.x, centroid.y, maxScale, pictureWidthPx, pictureHeightPx)
        }
        if (pan != Offset.Zero) {
            next = panBy(next, pan.x, pan.y, pictureWidthPx, pictureHeightPx)
        }
        zoom = next
    }

    fun reset() {
        zoom = PlayerZoom()
    }
}

@Composable
internal fun rememberPlayerZoomState(): PlayerZoomState = remember { PlayerZoomState() }

/**
 * The gestures of a picture: a pinch enlarges it around the fingers, a drag moves it, and a double
 * tap returns it to unit scale.
 *
 * The detector of the foundation hands out the values accumulated since the beginning of the
 * gesture, so the steps are counted here: the scale of the event is the ratio of the spread of the
 * fingers to the spread before, and the shift is the difference of the centroids.
 */
internal fun Modifier.playerZoomGestures(state: PlayerZoomState): Modifier = pointerInput(state) {
    val touchSlop = viewConfiguration.touchSlop
    var lastTapAtMs = Long.MIN_VALUE
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true) ?: return@awaitEachGesture
        var pointerCount = 1
        var multiTouch = false
        var centroid = down.position
        // The slop is counted from the place where the gesture began, not from the last event: a
        // slow drag crosses the slop in many small steps and none of them is big enough alone.
        var startCentroid = down.position
        var spread = 0f
        var pastSlop = false
        var moved = false
        var lastEventAtMs = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val positions = pressed.map { it.position }
            val newCentroid = positions.fold(Offset.Zero) { acc, position -> acc + position } / positions.size.toFloat()
            val newSpread = spreadOf(positions)
            if (pressed.size != pointerCount) {
                // A finger was added or lifted: the new spread is the baseline, the picture does not jump.
                pointerCount = pressed.size
                multiTouch = multiTouch || pressed.size > 1
                centroid = newCentroid
                startCentroid = newCentroid
                spread = newSpread
                lastEventAtMs = event.changes.maxOf { it.uptimeMillis }
                continue
            }
            val zoomChange = if (spread > 0f && newSpread > 0f) newSpread / spread else 1f
            val pan = newCentroid - centroid
            val travelled = (newCentroid - startCentroid).getDistance()
            if (!pastSlop && (zoomChange != 1f || travelled > touchSlop)) pastSlop = true
            if (pastSlop) {
                moved = true
                state.onGesture(newCentroid, pan, zoomChange)
            }
            centroid = newCentroid
            spread = newSpread
            lastEventAtMs = event.changes.maxOf { it.uptimeMillis }
        }
        if (!moved && !multiTouch) {
            if (lastEventAtMs - lastTapAtMs <= PLAYER_DOUBLE_TAP_TIMEOUT_MILLIS) {
                state.reset()
                lastTapAtMs = Long.MIN_VALUE
            } else {
                lastTapAtMs = lastEventAtMs
            }
        }
    }
}

/** The transform of the picture: the picture and its overlays grow together, the frame does not. */
internal fun Modifier.playerZoomTransform(state: PlayerZoomState): Modifier = this
    .onSizeChanged { state.onPictureSize(it.width, it.height) }
    .clipToBounds()
    .graphicsLayer {
        scaleX = state.zoom.scale
        scaleY = state.zoom.scale
        translationX = state.zoom.offsetX
        translationY = state.zoom.offsetY
    }

/** The average distance of the fingers from their centroid; it grows when the fingers spread. */
private fun spreadOf(positions: List<Offset>): Float {
    if (positions.size < 2) return 0f
    val centroid = positions.fold(Offset.Zero) { acc, position -> acc + position } / positions.size.toFloat()
    val spread = positions.fold(0f) { acc, position -> acc + (position - centroid).getDistance() }
    return spread / positions.size.toFloat()
}
