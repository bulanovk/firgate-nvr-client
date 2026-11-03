package com.frigate.android.feature.events

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.history.HistoryHlsDataSourceFactory
import kotlinx.coroutines.delay
import kotlin.math.abs

internal data class PreviewPlaybackStatus(
    val ready: Boolean = false,
    val frames: Long = 0,
    val positionSeconds: Double = 0.0,
    val error: String? = null,
    val released: Boolean = false,
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ReviewPreviewPlayer(
    profile: ServerProfile,
    cookie: String,
    preview: ReviewPreview,
    range: EventsRange,
    timestamp: Double? = null,
    playing: Boolean = true,
    rate: Float = 8f,
    onUnauthorized: () -> Unit,
    onStatus: (PreviewPlaybackStatus) -> Unit = {},
) {
    val owner = LocalContext.current as LifecycleOwner
    var started by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!started) return
    key(profile, cookie, preview, range) {
        ActiveReviewPreview(profile, cookie, preview, range, timestamp, playing, rate, onUnauthorized, onStatus)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ActiveReviewPreview(profile: ServerProfile, cookie: String, preview: ReviewPreview, range: EventsRange,
    timestamp: Double?, playing: Boolean, rate: Float, onUnauthorized: () -> Unit, onStatus: (PreviewPlaybackStatus) -> Unit) {
    val context = LocalContext.current
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val report by rememberUpdatedState(onStatus)
    val start = maxOf(preview.startTime, range.after)
    val end = minOf(preview.endTime, range.before)
    val url = eventsMediaUrl(profile, preview.src)
    if (url == null || end <= start) {
        LaunchedEffect(url, start, end) { report(PreviewPlaybackStatus(error = "Preview unavailable")) }
        return
    }
    var output: Surface? by remember { mutableStateOf(null) }
    var rendered by remember { mutableStateOf(false) }
    var updated by remember { mutableStateOf(false) }
    var frames by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ONE
            val source = ProgressiveMediaSource.Factory(HistoryHlsDataSourceFactory.create(profile, cookie))
                .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val failure = info.exception
                        return if (failure is HttpDataSource.InvalidResponseCodeException && failure.responseCode in 400..499) C.TIME_UNSET
                            else super.getRetryDelayMsFor(info)
                    }
                })
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(ClippingMediaSource(source, ((start - preview.startTime) * 1000000).toLong(), ((end - preview.startTime) * 1000000).toLong()))
        }
    }
    DisposableEffect(player) {
        var notified = false
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) {
                error = "Preview unavailable"
                var cause: Throwable? = failure
                while (cause != null) {
                    if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 401 && !notified) {
                        notified = true
                        unauthorized()
                    }
                    cause = cause.cause
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, surface: Any, renderTimeMs: Long) {
                if (surface === output) rendered = true
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
            report(PreviewPlaybackStatus(released = true))
        }
    }
    LaunchedEffect(player, playing, rate) { player.playWhenReady = playing; player.setPlaybackSpeed(rate) }
    LaunchedEffect(player, timestamp) {
        timestamp?.let { wall ->
            val position = (wall - start).coerceIn(0.0, end - start)
            if (abs(player.currentPosition / 1000.0 - position) > .75) {
                rendered = false
                updated = false
                player.seekTo((position * 1000).toLong())
            }
        }
    }
    LaunchedEffect(player, rendered, updated, error) {
        while (true) {
            report(PreviewPlaybackStatus(rendered && updated && error == null, frames, start + player.currentPosition / 1000.0, error))
            delay(250)
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        TextureView(ctx).apply {
            isClickable = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    rendered = false; updated = false
                    output = Surface(texture).also { player.setVideoSurface(it) }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    player.setVideoSurface(null)
                    output?.release(); output = null
                    rendered = false; updated = false
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { frames++; updated = true }
            }
        }
    }, onRelease = {
        it.surfaceTextureListener = null
        if (!player.isReleased) player.setVideoSurface(null)
        output?.release(); output = null
    })
}
