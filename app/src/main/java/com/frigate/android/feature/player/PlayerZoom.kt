package com.frigate.android.feature.player

/** The transform of a picture: a scale about the center of the frame and a shift in frame pixels. */
internal data class PlayerZoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal const val PLAYER_ZOOM_MIN_SCALE = 1f
internal const val PLAYER_ZOOM_MAX_SCALE = 10f
internal const val PLAYER_ZOOM_FALLBACK_SCALE = 4f

/**
 * The zoom limit follows the resolution of the source: no more than ~3 screen pixels per source pixel.
 * A 4K stream on a 1032 px wide picture gives 10x, a 1080p stream about 5.6x, a 360p stream nothing to zoom.
 */
internal fun playerZoomMaxScale(sourceWidthPx: Int, viewportWidthPx: Float): Float {
    if (sourceWidthPx <= 0 || viewportWidthPx <= 0f) return PLAYER_ZOOM_FALLBACK_SCALE
    return (3f * sourceWidthPx / viewportWidthPx).coerceIn(PLAYER_ZOOM_MIN_SCALE, PLAYER_ZOOM_MAX_SCALE)
}

/** Scales the picture around the focus point: the content under the finger stays under the finger. */
internal fun zoomAround(
    zoom: PlayerZoom,
    factor: Float,
    focusX: Float,
    focusY: Float,
    maxScale: Float,
    width: Float,
    height: Float,
): PlayerZoom {
    if (factor <= 0f || width <= 0f || height <= 0f) return zoom
    val limit = maxScale.coerceIn(PLAYER_ZOOM_MIN_SCALE, PLAYER_ZOOM_MAX_SCALE)
    val scale = (zoom.scale * factor).coerceIn(PLAYER_ZOOM_MIN_SCALE, limit)
    if (scale == zoom.scale) return zoom
    val centerX = width / 2f
    val centerY = height / 2f
    val ratio = scale / zoom.scale
    val offsetX = focusX - centerX - (focusX - centerX - zoom.offsetX) * ratio
    val offsetY = focusY - centerY - (focusY - centerY - zoom.offsetY) * ratio
    return clampZoom(PlayerZoom(scale, offsetX, offsetY), width, height)
}

/** Moves the picture; at unit scale there is nothing to move. */
internal fun panBy(zoom: PlayerZoom, dx: Float, dy: Float, width: Float, height: Float): PlayerZoom {
    if (zoom.scale <= PLAYER_ZOOM_MIN_SCALE || width <= 0f || height <= 0f) return zoom
    return clampZoom(zoom.copy(offsetX = zoom.offsetX + dx, offsetY = zoom.offsetY + dy), width, height)
}

/** Keeps the picture inside its own frame: the scaled content never leaves the area it covered. */
internal fun clampZoom(zoom: PlayerZoom, width: Float, height: Float): PlayerZoom {
    if (width <= 0f || height <= 0f) return zoom
    val limitX = (zoom.scale - 1f) * width / 2f
    val limitY = (zoom.scale - 1f) * height / 2f
    return zoom.copy(
        offsetX = zoom.offsetX.coerceIn(-limitX, limitX),
        offsetY = zoom.offsetY.coerceIn(-limitY, limitY),
    )
}

/** The zoom of a picture as it is announced: one decimal, as the PWA writes its scales. */
internal fun playerZoomLabel(scale: Float): String = "%.1fx".format(scale)
