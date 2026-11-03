package com.frigate.android.feature.live

import java.util.Locale

internal data class LivePlaybackStats(
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bufferedMs: Long = 0,
    val positionMs: Long = 0,
    val videoFrames: Int = 0,
    val droppedFrames: Int = 0,
    val audioBuffers: Int = 0,
    val muted: Boolean = true,
    val playing: Boolean = false,
    val speed: Float = 1f,
) {
    fun summary(): String =
        "MSE · ${width}×$height · ${videoCodec ?: "waiting"}\n" +
            "Buffer ${String.format(Locale.ROOT, "%.1f", bufferedMs / 1000.0)} s · Video frames $videoFrames\n" +
            audioSummary()

    fun audioSummary(): String = audioCodec?.let {
        "Audio: $it · ${if (muted) "muted" else "on"} · Output buffers $audioBuffers"
    } ?: "Audio: none"

    fun debugSummary(): String =
        "MSE · ${if (playing) "playing" else "not playing"}\n" +
            "Position $positionMs ms · Dropped frames $droppedFrames · Speed ${speed}x"
}
