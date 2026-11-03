package com.frigate.android

internal data class CameraBounds(
    val top: Float,
    val bottom: Float,
) {
    val height: Float
        get() = bottom - top
}

internal fun visibleCameraIds(
    viewportTop: Float,
    viewportBottom: Float,
    cameraBounds: Map<String, CameraBounds>,
    excludedKeys: Set<String> = emptySet(),
): Set<String> {
    if (viewportBottom <= viewportTop) return emptySet()

    return cameraBounds
        .filterKeys { key -> key !in excludedKeys }
        .filterValues { bounds ->
            if (bounds.height <= 0f) return@filterValues false
            val visibleStart = maxOf(bounds.top, viewportTop)
            val visibleEnd = minOf(bounds.bottom, viewportBottom)
            visibleEnd - visibleStart >= bounds.height * 0.5f
        }.keys
}
