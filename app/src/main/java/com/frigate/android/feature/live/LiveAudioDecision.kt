package com.frigate.android.feature.live

/** How long the app waits for the sound of a camera before it calls the camera silent. */
internal const val LIVE_AUDIO_OUTPUT_TIMEOUT_MS = 5_000L

/** How often the app checks whether the camera produces sound. */
internal const val AUDIO_OUTPUT_POLL_MS = 250L

/** What the app does with the audio of a camera while the user waits for the sound. */
internal enum class LiveAudioDecision {
    /** The camera has not produced audio yet: keep waiting. */
    WAITING,

    /** The decoder produced audio: the sound is there. */
    READY,

    /** The camera announced audio, but nothing arrived: the camera is silent. */
    UNAVAILABLE,
}

/**
 * The decision about the audio of a live camera.
 *
 * A camera can announce an audio stream that never carries samples: go2rtc reports the producer,
 * the track is in the stream, and the decoder gets nothing. The picture must not wait for it, and
 * the app must not promise sound it cannot deliver.
 *
 * @param decodedBuffers how many audio buffers the decoder has produced
 * @param waitedMs how long the app has been waiting for the sound
 */
internal fun liveAudioDecision(decodedBuffers: Int, waitedMs: Long): LiveAudioDecision = when {
    decodedBuffers > 0 -> LiveAudioDecision.READY
    waitedMs >= LIVE_AUDIO_OUTPUT_TIMEOUT_MS -> LiveAudioDecision.UNAVAILABLE
    else -> LiveAudioDecision.WAITING
}
