package com.frigate.android.feature.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor

sealed interface NativeMsePlaybackState {
    data object Connecting : NativeMsePlaybackState
    data object Buffering : NativeMsePlaybackState
    data object Playing : NativeMsePlaybackState
    data class Failed(val message: String) : NativeMsePlaybackState
}

private fun trackTypeName(type: Int): String = when (type) {
    androidx.media3.common.C.TRACK_TYPE_AUDIO -> "audio"
    androidx.media3.common.C.TRACK_TYPE_VIDEO -> "video"
    androidx.media3.common.C.TRACK_TYPE_TEXT -> "text"
    else -> "type$type"
}

@UnstableApi
class NativeMsePlayer(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    private val onStateChanged: (NativeMsePlaybackState) -> Unit,
    private val onVideoAspectChanged: (Float) -> Unit = {},
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    audioEnabled: Boolean = false,
) {
    private val readiness = LiveOutputReadiness()
    private var output: Surface? = null
    private var onOutputReady: ((Boolean) -> Unit)? = null
    private val analyticsListener = object : AnalyticsListener {
        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: androidx.media3.common.Tracks,
        ) {
            if (released) return
            Log.i(
                LOG_TAG,
                "tracks=" + tracks.groups.joinToString("; ") { group ->
                    "${trackTypeName(group.type)}{" + (0 until group.length).joinToString(",") { index ->
                        val format = group.getTrackFormat(index)
                        "${format.sampleMimeType}/${format.sampleRate}Hz/${format.channelCount}ch" +
                            (if (group.isTrackSelected(index)) "/selected" else "/not-selected")
                    } + "}"
                },
            )
        }

        override fun onAudioDisabled(
            eventTime: AnalyticsListener.EventTime,
            counters: androidx.media3.exoplayer.DecoderCounters,
        ) {
            Log.i(LOG_TAG, "audioDisabled counters=$counters")
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
        ) {
            Log.i(LOG_TAG, "audioTrackInitialized")
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (released || this@NativeMsePlayer.output !== output) return
            readiness.rendered(output)
            onOutputReady?.invoke(readiness.ready)
            Log.i(LOG_TAG, "firstFrame player=${System.identityHashCode(this@NativeMsePlayer)} timeMs=$renderTimeMs")
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    private val sourceFactory = ProgressiveMediaSource.Factory(
        dataSourceFactory,
        ExtractorsFactory { arrayOf(FragmentedMp4Extractor()) },
    )
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(
                LOG_TAG,
                "state=$stateName positionMs=${player.currentPosition} bufferedPositionMs=${player.bufferedPosition} speed=${player.playbackParameters.speed}",
            )
            when (playbackState) {
                Player.STATE_BUFFERING -> onStateChanged(NativeMsePlaybackState.Buffering)
                Player.STATE_READY -> {
                    onStateChanged(NativeMsePlaybackState.Playing)
                    updatePlaybackRate()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!released && videoSize.width > 0 && videoSize.height > 0) {
                onVideoAspectChanged(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!released) {
                Log.i(LOG_TAG, "error=${error.errorCodeName} message=${error.message}")
                onOutputReady?.invoke(false)
                onStateChanged(NativeMsePlaybackState.Failed("Live video unavailable"))
            }
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MseLiveBufferPolicy.minimumBufferMs.toInt(),
                    MseLiveBufferPolicy.maximumBufferMs.toInt(),
                    MseLiveBufferPolicy.bufferForPlaybackMs.toInt(),
                    MseLiveBufferPolicy.bufferForPlaybackAfterRebufferMs.toInt(),
                )
                .setBackBuffer(MseLiveBufferPolicy.retainedWindowMs.toInt(), true)
                .build(),
        )
        .setMediaSourceFactory(sourceFactory)
        .build()
        .also {
            it.addListener(playerListener)
            it.addAnalyticsListener(analyticsListener)
        }

    init {
        // The audio track is off before the stream starts: a track that never gets samples keeps
        // the player out of the ready state, and the picture has to play anyway.
        setAudioEnabled(audioEnabled)
    }

    private val playbackRateUpdater = object : Runnable {
        override fun run() {
            if (released) return
            updatePlaybackRate()
            mainHandler.postDelayed(this, PLAYBACK_RATE_UPDATE_MS)
        }
    }

    internal fun playbackStats(): LivePlaybackStats {
        val video = player.videoDecoderCounters?.also { it.ensureUpdated() }
        val audio = player.audioDecoderCounters?.also { it.ensureUpdated() }
        return LivePlaybackStats(
            width = player.videoSize.width,
            height = player.videoSize.height,
            videoCodec = player.videoFormat?.sampleMimeType,
            audioCodec = player.audioFormat?.sampleMimeType,
            bufferedMs = player.totalBufferedDuration,
            positionMs = player.currentPosition,
            videoFrames = video?.renderedOutputBufferCount ?: 0,
            droppedFrames = video?.droppedBufferCount ?: 0,
            audioBuffers = audio?.renderedOutputBufferCount ?: 0,
            muted = player.volume == 0f,
            playing = player.isPlaying,
            speed = player.playbackParameters.speed,
        )
    }

    /**
     * Turns the audio track of the stream on and off.
     *
     * A muted track still reaches the renderer, and a renderer that never gets audio keeps the
     * whole player out of the ready state: the picture stops with it. A camera may announce an
     * audio stream that never carries samples, so the track stays disabled until the user asks
     * for the sound and the sound proves to be there.
     */
    fun setAudioEnabled(enabled: Boolean) {
        if (released) return
        val parameters = player.trackSelectionParameters
        if (parameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO) == !enabled) return
        player.trackSelectionParameters = parameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !enabled)
            .build()
    }

    fun attachOutput(surface: Surface, onReady: (Boolean) -> Unit) {
        if (released || output === surface) return
        output?.let(::detachOutput)
        output = surface
        readiness.attach(surface)
        onOutputReady = onReady
        onReady(false)
        player.setVideoSurface(surface)
    }

    fun detachOutput(surface: Surface) {
        if (output !== surface) return
        readiness.detach(surface)
        onOutputReady?.invoke(false)
        onOutputReady = null
        output = null
        if (!released) player.clearVideoSurface(surface)
    }

    fun prepareAndPlay() {
        if (released) return
        onStateChanged(NativeMsePlaybackState.Connecting)
        player.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(PLACEHOLDER_MEDIA_URI)))
        player.prepare()
        player.playWhenReady = true
        mainHandler.removeCallbacks(playbackRateUpdater)
        mainHandler.post(playbackRateUpdater)
    }

    fun release() {
        if (released) return
        output?.let(::detachOutput)
        released = true
        mainHandler.removeCallbacks(playbackRateUpdater)
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        Log.i(LOG_TAG, "release player=${System.identityHashCode(this)}")
        player.release()
    }

    private fun updatePlaybackRate() {
        if (player.playbackState != Player.STATE_READY) return
        val lagMs = player.bufferedPosition - player.currentPosition
        val rate = MseLiveBufferPolicy.playbackRateForLag(lagMs)
        if (player.playbackParameters.speed != rate) {
            Log.i(LOG_TAG, "playbackRate=$rate lagMs=$lagMs")
            player.playbackParameters = PlaybackParameters(rate)
        }
    }

    private companion object {
        const val LOG_TAG = "FrigateNativeMSE"
        const val PLAYBACK_RATE_UPDATE_MS = 500L
        const val PLACEHOLDER_MEDIA_URI = "https://mse.invalid/live"
    }
}
