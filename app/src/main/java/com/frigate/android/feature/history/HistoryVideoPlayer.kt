package com.frigate.android.feature.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.VolumeOff
import com.frigate.android.ui.icons.VolumeUp
import com.frigate.android.ui.icons.Forward10
import com.frigate.android.ui.icons.Fullscreen
import com.frigate.android.ui.icons.FullscreenExit
import com.frigate.android.ui.icons.Pause
import com.frigate.android.ui.icons.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import com.frigate.android.ui.icons.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.core.server.TimelineSpan
import com.frigate.android.feature.player.PlayerZoomState
import com.frigate.android.feature.player.playerZoomGestures
import com.frigate.android.feature.player.playerZoomLabel
import com.frigate.android.feature.player.playerZoomTransform
import com.frigate.android.feature.player.rememberPlayerZoomState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

// The clock of the timeline shows whole seconds: one step a second is all the playhead needs.
private const val POSITION_POLL_MILLIS = 1_000L

// The controls hide after four seconds: a quarter of a second is a fine grain for that.
private const val CONTROLS_POLL_MILLIS = 250L

/**
 * The archive player.
 *
 * The playlist covers one hour chunk of the archive. Every source identity — the playlist URL and
 * the retry counter — opens a new generation: the listener of the retired generation is removed
 * before the next one is added, so a late event of an old playlist cannot change the screen.
 *
 * The two positions of the player answer different questions: a rebuilt source continues at the
 * playhead, an explicit seek of the user lands on the anchor of the session.
 */
@UnstableApi
@Composable
internal fun HistoryVideoPlayer(
    profile: ServerProfile,
    cookie: String,
    playlistUrl: String?,
    startPositionSeconds: () -> Double?,
    seekPositionSeconds: Double?,
    seekToken: Int,
    segments: List<TimelineSpan>,
    inpointOffset: Double,
    controller: HistoryPlayerController,
    onSeekBy: (Double) -> Unit,
    onEnded: () -> Unit,
    onError: (HistoryError) -> Unit,
    onSnapshot: (Bitmap) -> Unit,
    onFullscreen: () -> Unit,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    zoom: PlayerZoomState = rememberPlayerZoomState(),
) {
    val context = LocalContext.current
    val dataSourceFactory = remember(profile, cookie) { HistoryHlsDataSourceFactory.create(profile, cookie) }
    val player = remember(profile, cookie) { ExoPlayer.Builder(context).build() }
    val currentOnError = rememberUpdatedState(onError)
    val currentOnSnapshot = rememberUpdatedState(onSnapshot)
    val currentOnEnded = rememberUpdatedState(onEnded)

    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var hasFrame by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<HistoryTextureView?>(null) }
    // The open menu belongs to the controls: the controls stay while it is open, otherwise the
    // menu would close under the finger of the user four seconds after the tap on the rate.
    var rateMenuOpen by remember { mutableStateOf(false) }
    // The zoom of the archive is the same zoom as the zoom of the live panel: a property of the
    // player, not of the screen.

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(playlistUrl, controller.retryToken) {
        val url = playlistUrl
        // Another recording is another frame: the zoom of the previous one does not fit it.
        zoom.reset()
        if (url == null) {
            hasFrame = false
            controller.onLoading(true)
            awaitCancellation()
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnError.value(historyErrorFor(error))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> controller.onLoading(true)
                    Player.STATE_READY -> controller.onLoading(false)
                    Player.STATE_ENDED -> currentOnEnded.value()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    videoWidth = videoSize.width
                }
            }

            override fun onRenderedFirstFrame() {
                hasFrame = true
            }
        }
        player.addListener(listener)
        try {
            hasFrame = false
            controller.onLoading(true)
            val source = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(source)
            player.prepare()
            startPositionSeconds()?.let { player.seekTo((it * 1000).toLong()) }
            awaitCancellation()
        } finally {
            player.removeListener(listener)
        }
    }

    // The explicit seek of the user moves the player inside the manifest that is already open. The
    // key is the seek counter alone: the position of the playhead changes with every frame and
    // would turn the effect into a seek that runs four times a second.
    LaunchedEffect(seekToken) {
        if (player.playbackState == Player.STATE_IDLE) return@LaunchedEffect
        seekPositionSeconds?.let { player.seekTo((it * 1000).toLong()) }
    }

    // The limit of the zoom follows the resolution of the recording: a 4K recording can be
    // enlarged ten times, a 360p one cannot be enlarged at all.
    LaunchedEffect(videoWidth) { zoom.onSourceSize(videoWidth) }

    LaunchedEffect(player, segments, inpointOffset, playlistUrl) {
        while (true) {
            delay(POSITION_POLL_MILLIS)
            if (!hasFrame) continue
            controller.onPositionChanged(
                historyWallClockFor(player.currentPosition / 1000.0, segments, inpointOffset),
            )
        }
    }

    LaunchedEffect(controller.playback.playing) { player.playWhenReady = controller.playback.playing }
    LaunchedEffect(controller.playback.muted) { player.volume = if (controller.playback.muted) 0f else 1f }
    LaunchedEffect(controller.playback.speed) { player.setPlaybackSpeed(controller.playback.speed) }

    LaunchedEffect(controller) {
        while (true) {
            delay(CONTROLS_POLL_MILLIS)
            controller.onControlsExpired()
        }
    }

    val state = controller.playback
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        // The picture carries the zoom: the controls of the archive stay outside of it and do not
        // grow with the frame, as in the PWA the header does not grow with the picture.
        Box(
            Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspect)
                // The zoom is announced: a screen reader has to say how far the picture is
                // enlarged, and the test has to see that the fingers reached the picture.
                .semantics { contentDescription = "Zoom ${playerZoomLabel(zoom.zoom.scale)}" }
                .playerZoomGestures(zoom)
                .playerZoomTransform(zoom),
        ) {
            AndroidView(
                factory = { viewContext ->
                    HistoryTextureView(
                        viewContext,
                        player,
                        { surface ->
                            if (surface != null) {
                                player.setVideoSurface(surface)
                            } else {
                                player.clearVideoSurface()
                            }
                        },
                        { controller.onFrameTapped() },
                    ).also { textureView = it }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view -> view.releaseSurface() },
                update = {},
            )
        }

        if (state.loading && !hasFrame) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        state.error?.let { error ->
            HistoryVideoOverlay(
                message = error.message(),
                modifier = Modifier.align(Alignment.Center),
                onRetry = controller::retry,
            )
        }

        if (state.controlsVisible || rateMenuOpen) {
            HistoryPlaybackControls(
                playing = state.playing,
                muted = state.muted,
                speed = state.speed,
                rateMenuOpen = rateMenuOpen,
                onRateMenuChange = { open -> rateMenuOpen = open },
                onToggleMute = controller::toggleMute,
                onRewind = { onSeekBy(-10.0) },
                onTogglePlay = controller::togglePlay,
                onForward = { onSeekBy(10.0) },
                onSpeed = controller::setSpeed,
                onSnapshot = {
                    textureView?.currentBitmap()?.let(currentOnSnapshot.value)
                },
                onFullscreen = onFullscreen,
                fullscreen = fullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun HistoryPlaybackControls(
    playing: Boolean,
    muted: Boolean,
    speed: Float,
    rateMenuOpen: Boolean,
    onRateMenuChange: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSnapshot: () -> Unit,
    onFullscreen: () -> Unit,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.padding(bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryControlButton(
                icon = if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                label = if (muted) "Unmute" else "Mute",
                onClick = onToggleMute,
            )
            HistoryControlButton(
                icon = Icons.Outlined.Replay10,
                label = "Back 10 seconds",
                onClick = onRewind,
            )
            HistoryControlButton(
                icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                label = if (playing) "Pause" else "Play",
                onClick = onTogglePlay,
            )
            HistoryControlButton(
                icon = Icons.Outlined.Forward10,
                label = "Forward 10 seconds",
                onClick = onForward,
            )
            Box {
                TextButton(
                    onClick = { onRateMenuChange(!rateMenuOpen) },
                    modifier = Modifier.width(48.dp).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        historyRateLabel(speed),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                // The rates live in a dropdown of their own window: inside the player box the menu
                // is cut off by the height of the video and the slow rates cannot be reached.
                // The menu keeps the dark surface of the archive: the app itself has no theme, so
                // the default light one would put the light text of the rates on a light panel.
                MaterialTheme(colorScheme = historyMenuColorScheme) {
                    DropdownMenu(
                        expanded = rateMenuOpen,
                        onDismissRequest = { onRateMenuChange(false) },
                    ) {
                        HISTORY_SPEEDS.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        historyRateLabel(option),
                                        color = if (option == speed) {
                                            HistoryColors.foreground
                                        } else {
                                            HistoryColors.muted
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                onClick = {
                                    onSpeed(option)
                                    onRateMenuChange(false)
                                },
                            )
                        }
                    }
                }
            }
            HistoryControlButton(
                icon = Icons.Outlined.PhotoCamera,
                label = "Save a frame",
                onClick = onSnapshot,
            )
            HistoryControlButton(
                icon = if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                label = if (fullscreen) "Leave fullscreen" else "Fullscreen",
                onClick = onFullscreen,
            )
        }
    }
}

@Composable
private fun HistoryControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
private fun HistoryVideoOverlay(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry, modifier = Modifier.height(48.dp)) {
                Text("Retry", color = Color.White)
            }
        }
    }
}

internal fun HistoryError.message(): String = when (this) {
    HistoryError.NoRecording -> "No recording for this time"
    HistoryError.Forbidden -> "This server does not allow archive playback"
    HistoryError.Unauthorized -> "The session expired. Sign in again."
    is HistoryError.Network -> message
    is HistoryError.Codec -> message
}

private fun historyErrorFor(error: PlaybackException): HistoryError {
    val responseCode = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    if (responseCode != null) {
        return classifyHistoryError(HistoryFailureKind.HTTP, responseCode, error.errorCodeName)
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> HistoryError.Codec("This camera's stream cannot be decoded on this device.")

        else -> HistoryError.Network(error.errorCodeName)
    }
}

/** The surface of the archive player; a texture keeps the overlay controls above the picture. */
private class HistoryTextureView(
    context: Context,
    private val player: ExoPlayer,
    private val onSurfaceChanged: (Surface?) -> Unit,
    private val onTapped: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val tapDetector = HistoryFrameTap(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    init {
        surfaceTextureListener = this
    }

    /**
     * The tap on the picture belongs to the surface itself.
     *
     * The controls are drawn by the Compose tree above the surface, but a clickable layer of that
     * tree over the video takes the hit away from the buttons of the controls: they stop answering
     * the finger. The surface reports the tap and stays unconsumed, so the buttons of the controls
     * keep their own taps.
     *
     * The tap is counted on the release and only when one finger did the job: a pinch is a zoom of
     * the picture and must not throw the controls away.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> FrameTouchAction.Down
            MotionEvent.ACTION_MOVE -> FrameTouchAction.Move
            MotionEvent.ACTION_UP -> FrameTouchAction.Up
            MotionEvent.ACTION_CANCEL -> FrameTouchAction.Cancel
            MotionEvent.ACTION_POINTER_DOWN -> FrameTouchAction.PointerDown
            MotionEvent.ACTION_POINTER_UP -> FrameTouchAction.PointerUp
            else -> return false
        }
        if (tapDetector.onAction(action, event.x, event.y, event.eventTime)) onTapped()
        return false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val created = Surface(texture)
        surface = created
        this.texture = texture
        onSurfaceChanged(created)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        updateBufferSize()
    }

    /**
     * The buffer of the surface keeps the frame in the resolution of the recording: without it the
     * decoded frame is shrunk to the size of the view before the user sees it, and the zoom has
     * nothing to enlarge.
     */
    private fun updateBufferSize() {
        val texture = texture ?: return
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0) return
        if (size.width == bufferWidth && size.height == bufferHeight) return
        bufferWidth = size.width
        bufferHeight = size.height
        texture.setDefaultBufferSize(size.width, size.height)
    }

    fun releaseSurface() {
        surface?.let {
            onSurfaceChanged(null)
            it.release()
        }
        surface = null
        texture = null
    }

    fun currentBitmap(): Bitmap? = if (isAvailable) getBitmap() else null
}
