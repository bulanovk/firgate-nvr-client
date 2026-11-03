package com.frigate.android.feature.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.LiveStreamInfo
import androidx.media3.common.AudioAttributes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.frigate.android.CameraBounds
import com.frigate.android.visibleCameraIds
import com.frigate.android.core.server.FrigateCamera
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.LiveStreamEndpoint
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.review.REVIEW_CAROUSEL_ITEM_KEY
import com.frigate.android.feature.review.ReviewCarousel
import com.frigate.android.feature.review.ReviewCarouselMode
import com.frigate.android.feature.review.ReviewUiState
import com.frigate.android.feature.review.reviewCarouselMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal data class SnapshotEntry(
    val bitmap: Bitmap?,
    val failed: Boolean,
    val completedAtMs: Long,
    val loadTimeMs: Long,
    val offline: Boolean = false,
)

/** The player of a camera with its state and its still: the tile of the grid and the panel share it. */
internal class LiveCameraStreamState(
    val visual: LiveTileVisualState,
    val snapshot: ImageBitmap?,
    val video: @Composable () -> Unit,
)

@UnstableApi
@Composable
internal fun LiveCameraScreen(
    profile: ServerProfile,
    cookie: String,
    cameras: List<FrigateCamera>,
    modifier: Modifier = Modifier,
    activity: Map<String, CameraActivity>,
    reviews: ReviewUiState = ReviewUiState(),
    selectedCameraId: String? = null,
    onSelectedCameraChange: (String?) -> Unit = {},
    onOpenHistory: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
    scroll: ScrollState = rememberScrollState(),
    onOpenReview: (FrigateReview) -> Unit = {},
    onMarkViewed: (String) -> Unit = {},
    onRetryReviews: () -> Unit = {},
    role: String = "viewer",
    onSetControl: (String, CameraControl, Boolean) -> Boolean = { _, _, _ -> false },
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    pictureInPicture: Boolean = false,
    onPictureInPicture: ((Float, android.graphics.Rect) -> Unit)? = null,
    onUnauthorized: () -> Unit = {},
) {
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val selectedStreams = remember(profile, cookie) { mutableStateMapOf<String, String>() }
    val playbackCameras = cameras.map { camera ->
        val stream = selectedStreams[camera.id]?.takeIf { it in camera.streams.values }
        if (stream == null || camera.id != selectedCameraId) camera else camera.copy(streamName = stream)
    }
    val selectedCamera = playbackCameras.firstOrNull { it.id == selectedCameraId }
    // Fullscreen is owned with the app chrome; Back leaves it before closing the camera.
    BackHandler(enabled = selectedCamera != null && !pictureInPicture) {
        if (fullscreen) onFullscreenChange(false) else onSelectedCameraChange(null)
    }
    val snapshots = remember(profile, cookie) { mutableStateMapOf<String, SnapshotEntry>() }
    val pool = remember(profile, cookie) { LiveCameraPlayerPool<NativeMsePlayer> { it.release() } }
    val statuses = remember(profile, cookie) { mutableStateMapOf<String, NativeMsePlaybackState>() }
    val aspects = remember(profile, cookie) { mutableStateMapOf<String, Float>() }
    val videoWidths = remember(profile, cookie) { mutableStateMapOf<String, Int>() }
    val retries = remember(profile, cookie) { mutableStateMapOf<String, Int>() }
    val httpClient = remember { createLiveWebSocketClient() }
    val codecOffer = remember { Go2RtcMseProtocol.deviceVideoCodecOffer() }
    val audioCodecOffer = remember { Go2RtcMseProtocol.deviceCodecOffer(includeAudio = true) }
    var muted by remember(profile, cookie, selectedCamera?.id, selectedCamera?.streamName) { mutableStateOf(true) }
    var streamInfo by remember(profile, cookie, selectedCamera?.streamName, started) { mutableStateOf<LiveStreamInfo?>(null) }
    LaunchedEffect(profile, cookie, selectedCamera?.streamName, started) {
        val streamName = selectedCamera?.streamName
        if (streamName != null && started) {
            try {
                streamInfo = withContext(Dispatchers.IO) { FrigateClient(profile, cookie).liveStreamInfo(streamName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An expired session is renewed by the app, not reported as a broken camera.
                if (error is FrigateHttpException && error.code == 401) onUnauthorized()
                else onMessage("Could not load stream capabilities")
            }
        }
    }
    val detailCodecOffer = if (streamInfo?.audioOutput == true) audioCodecOffer else codecOffer
    var generation by remember(profile, cookie) { mutableStateOf(0) }
    var visibleIds by remember { mutableStateOf(emptySet<String>()) }
    var playback by remember(profile, cookie) { mutableStateOf(emptyMap<String, LiveTilePlayback>()) }
    val nonCameraKeys = remember { setOf(REVIEW_CAROUSEL_ITEM_KEY) }

    // Every card is composed at once (as in the PWA): bounds come from layout, in content coordinates.
    // While a camera is open the grid is not shown, so nothing counts as visible in the grid.
    val gridShown = selectedCamera == null
    val cardBounds = remember { mutableStateMapOf<String, CameraBounds>() }
    val contentOrigin = remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(scroll, gridShown) {
        snapshotFlow {
            if (!gridShown) return@snapshotFlow emptySet()
            visibleCameraIds(
                scroll.value.toFloat(), (scroll.value + viewportHeight).toFloat(),
                cardBounds.toMap(),
                excludedKeys = nonCameraKeys,
            )
        }.collect { visibleIds = it }
    }
    LaunchedEffect(playbackCameras, activity, visibleIds, selectedCameraId, started, codecOffer, snapshots.mapValues { it.value.offline }) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            playback = playbackCameras.associate { camera ->
                val runtime = activity[camera.id] ?: CameraActivity()
                val enabled = runtime.enabled ?: camera.enabled
                val offline = runtime.offline ?: snapshots[camera.id]?.offline ?: false
                val visible = selectedCamera?.let { it.id == camera.id } ?: (camera.id in visibleIds)
                val allowed = started && visible && enabled && !offline &&
                    camera.streamName.isNotBlank() && codecOffer.isNotBlank()
                val demand = if (selectedCamera?.id == camera.id) allowed
                    else liveTileDemand(allowed, enabled, runtime, camera.streamName)
                camera.id to (playback[camera.id] ?: LiveTilePlayback()).update(demand, allowed, now)
            }
            val nextStop = playback.values.mapNotNull { it.stopAtMs }.minOrNull() ?: break
            delay((nextStop - now).coerceAtLeast(1))
        }
    }
    fun playerKey(camera: FrigateCamera): String = "${camera.id}\u0000${camera.streamName}\u0000$generation\u0000${retries[camera.id] ?: 0}\u0000${if (camera.id == selectedCameraId) detailCodecOffer else codecOffer}"
    val retainedKeys = playbackCameras.filter { started && playback[it.id]?.keepPlayer == true }.mapTo(mutableSetOf(), ::playerKey)
    LaunchedEffect(pool, retainedKeys) {
        pool.retain(retainedKeys)
        statuses.keys.retainAll(retainedKeys)
        aspects.keys.retainAll(retainedKeys)
        videoWidths.keys.retainAll(retainedKeys)
    }
    DisposableEffect(pool, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    generation++
                    started = true
                }
                Lifecycle.Event.ON_STOP -> {
                    started = false
                    playback = emptyMap()
                    pool.releaseAll()
                    statuses.clear()
                    aspects.clear()
                    videoWidths.clear()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pool.releaseAll()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val expanded = maxWidth >= 600.dp
        if (selectedCamera != null) {
            LiveCameraPanel(
                camera = selectedCamera,
                profile = profile,
                cookie = cookie,
                activity = activity[selectedCamera.id] ?: CameraActivity(),
                visible = started,
                expanded = expanded,
                keepPlayer = started && playback[selectedCamera.id]?.keepPlayer == true,
                playerKey = playerKey(selectedCamera),
                codecOffer = detailCodecOffer,
                audioAvailable = streamInfo?.audioOutput == true,
                muted = muted,
                onMutedChange = { muted = it },
                httpClient = httpClient,
                pool = pool,
                statuses = statuses,
                aspects = aspects,
                videoWidths = videoWidths,
                snapshots = snapshots,
                fullscreen = fullscreen,
                onFullscreenChange = onFullscreenChange,
                pictureInPicture = pictureInPicture,
                onPictureInPicture = onPictureInPicture,
                onRetry = { retries[selectedCamera.id] = (retries[selectedCamera.id] ?: 0) + 1 },
                onBack = { onSelectedCameraChange(null) },
                onOpenHistory = { onOpenHistory(selectedCamera.id) },
                onMessage = onMessage,
                role = role,
                onSetControl = { control, value -> onSetControl(selectedCamera.id, control, value) },
                onStreamChange = { stream -> selectedStreams[selectedCamera.id] = stream },
                onUnauthorized = onUnauthorized,
            )
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Row {
                    Text("Live", Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                if (cameras.isEmpty()) {
                    Text("No cameras available", Modifier.padding(16.dp), color = Color.LightGray)
                } else {
                    val columns = if (expanded) 2 else 1
                    Column(
                        Modifier.fillMaxWidth().weight(1f)
                            .onSizeChanged { viewportHeight = it.height }
                            .verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().onGloballyPositioned { contentOrigin.value = it }) {
                            if (reviewCarouselMode(reviews) != ReviewCarouselMode.HIDDEN) {
                                ReviewCarousel(
                                    state = reviews,
                                    profile = profile,
                                    cookie = cookie,
                                    cameraNames = cameras.associate { camera -> camera.id to camera.name },
                                    onOpen = onOpenReview,
                                    onMarkViewed = onMarkViewed,
                                    onRetry = onRetryReviews,
                                )
                            }
                        }
                        cameras.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top) {
                                row.forEach { camera ->
                                    Box(Modifier.weight(1f).onGloballyPositioned { coords ->
                                        val origin = contentOrigin.value ?: return@onGloballyPositioned
                                        val top = coords.positionInRoot().y - origin.positionInRoot().y
                                        val bounds = CameraBounds(top, top + coords.size.height)
                                        if (cardBounds[camera.id] != bounds) cardBounds[camera.id] = bounds
                                    }) {
                                        LiveCameraCard(
                                            camera, profile, cookie, activity[camera.id] ?: CameraActivity(),
                                            started && camera.id in visibleIds, expanded,
                                            started && playback[camera.id]?.keepPlayer == true,
                                            playerKey(camera), codecOffer, httpClient, pool, statuses, aspects, videoWidths, snapshots,
                                            onRetry = { retries[camera.id] = (retries[camera.id] ?: 0) + 1 },
                                            onOpen = { onSelectedCameraChange(camera.id) },
                                        )
                                    }
                                }
                                // An odd last camera keeps the width of a column, as in the grid.
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun LiveCameraCard(
    camera: FrigateCamera,
    profile: ServerProfile,
    cookie: String,
    activity: CameraActivity,
    visible: Boolean,
    expanded: Boolean,
    keepPlayer: Boolean,
    playerKey: String,
    codecOffer: String,
    httpClient: OkHttpClient,
    pool: LiveCameraPlayerPool<NativeMsePlayer>,
    statuses: MutableMap<String, NativeMsePlaybackState>,
    aspects: MutableMap<String, Float>,
    videoWidths: MutableMap<String, Int>,
    snapshots: MutableMap<String, SnapshotEntry>,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    val stream = LiveCameraStream(
        camera, profile, cookie, activity, visible, keepPlayer, playerKey, codecOffer, httpClient,
        pool, statuses, aspects, videoWidths, snapshots, manual = false,
    )
    LiveCameraTile(
        stream.visual,
        stream.snapshot,
        modifier = Modifier.clickable(onClickLabel = "Open ${camera.name}", onClick = onOpen),
        onRetry = onRetry,
    ) {
        stream.video()
    }
}

/**
 * The player of a camera: the tile of the grid and the panel of the camera use the same player,
 * the same still and the same state.
 */
@UnstableApi
@Composable
internal fun LiveCameraStream(
    camera: FrigateCamera,
    profile: ServerProfile,
    cookie: String,
    activity: CameraActivity,
    visible: Boolean,
    keepPlayer: Boolean,
    playerKey: String,
    codecOffer: String,
    httpClient: OkHttpClient,
    pool: LiveCameraPlayerPool<NativeMsePlayer>,
    statuses: MutableMap<String, NativeMsePlaybackState>,
    aspects: MutableMap<String, Float>,
    videoWidths: MutableMap<String, Int>,
    snapshots: MutableMap<String, SnapshotEntry>,
    manual: Boolean,
    fullResolution: Boolean = false,
    onSurface: (MseTextureView?) -> Unit = {},
    audioMuted: Boolean = true,
    onAudioUnavailable: () -> Unit = {},
): LiveCameraStreamState {
    val context = LocalContext.current
    val enabled = activity.enabled ?: camera.enabled
    val snapshot = snapshots[camera.id]
    val offline = activity.offline ?: snapshot?.offline ?: false
    val player = remember(pool, playerKey, keepPlayer) {
        if (!keepPlayer) null else pool.getOrCreate(playerKey) {
            NativeMsePlayer(
                context,
                Go2RtcMseDataSourceFactory(
                    LiveStreamEndpoint.mseWebSocketUrl(profile, camera.streamName), cookie, codecOffer, httpClient,
                    onMimeType = {},
                ),
                onStateChanged = { statuses[playerKey] = it },
                onVideoAspectChanged = { aspects[playerKey] = it },
                onVideoSizeChanged = { width, _ -> videoWidths[playerKey] = width },
                // The sound is off until the user asks for it: a camera that announces audio and
                // delivers nothing must not hold the picture back.
                audioEnabled = false,
            )
        }
    }
    LaunchedEffect(player, audioMuted) {
        val active = player ?: return@LaunchedEffect
        active.setAudioEnabled(!audioMuted)
        active.player.apply {
            volume = if (audioMuted) 0f else 1f
            setAudioAttributes(AudioAttributes.DEFAULT, !audioMuted)
        }
    }
    // The sound of the camera has to prove itself: a camera may announce an audio stream that
    // never carries samples. The track is off again and the user is told, instead of a promise
    // that never comes true.
    LaunchedEffect(player, audioMuted) {
        val active = player ?: return@LaunchedEffect
        if (audioMuted) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(AUDIO_OUTPUT_POLL_MS)
            val waited = SystemClock.elapsedRealtime() - startedAt
            val decision = liveAudioDecision(active.playbackStats()?.audioBuffers ?: 0, waited)
            when (decision) {
                LiveAudioDecision.WAITING -> Unit
                LiveAudioDecision.READY -> return@LaunchedEffect
                LiveAudioDecision.UNAVAILABLE -> {
                    active.setAudioEnabled(false)
                    onAudioUnavailable()
                    return@LaunchedEffect
                }
            }
        }
    }
    var outputReady by remember(player) { mutableStateOf(false) }
    val active = manual || activity.activeMotion || activity.activeTracking
    val failed = statuses[playerKey] is NativeMsePlaybackState.Failed ||
        (visible && active && (codecOffer.isBlank() || camera.streamName.isBlank()))
    val ready = player != null && outputReady && !failed && enabled && !offline
    val interval = liveSnapshotInterval(visible, enabled, offline, active, ready, attempted = snapshot != null)
    val currentInterval by rememberUpdatedState(interval)
    LaunchedEffect(profile, cookie, camera.id, interval >= 0) {
        if (interval < 0) return@LaunchedEffect
        pollCameraSnapshots(
            intervals = snapshotFlow { currentInterval },
            nextDelayMs = { nextInterval ->
                val previous = snapshots[camera.id]
                if (previous == null) 0L else snapshotRefreshDelayMs(
                    previous.completedAtMs, SystemClock.elapsedRealtime(),
                    snapshotDelayAfterLoad(nextInterval, previous.loadTimeMs, previous.failed),
                )
            },
        ) {
            val start = SystemClock.elapsedRealtime()
            var refreshFailed = false
            try {
                val image = CameraSnapshotLoader.load(profile, camera.id, cookie,
                    camera.detectHeight.takeIf { it > 0 }?.coerceAtMost(720) ?: 720, System.currentTimeMillis())
                val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) }
                refreshFailed = bitmap == null
                snapshots[camera.id] = SnapshotEntry(
                    bitmap ?: snapshots[camera.id]?.bitmap, refreshFailed, SystemClock.elapsedRealtime(),
                    SystemClock.elapsedRealtime() - start, image.offline,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                refreshFailed = true
                snapshots[camera.id] = SnapshotEntry(snapshots[camera.id]?.bitmap, true, SystemClock.elapsedRealtime(),
                    SystemClock.elapsedRealtime() - start)
            }
        }
    }
    val stillAspect = snapshot?.bitmap?.let { it.width.toFloat() / it.height }
        ?: if (camera.detectWidth > 0 && camera.detectHeight > 0) camera.detectWidth.toFloat() / camera.detectHeight else 16f / 9
    return LiveCameraStreamState(
        visual = LiveTileVisualState(
            name = camera.name,
            tileAspect = liveTileAspect(camera.detectWidth, camera.detectHeight),
            pictureAspect = if (ready) aspects[playerKey] ?: stillAspect else stillAspect,
            expanded = false, enabled = enabled, offline = offline,
            motion = activity.activeMotion, tracking = activity.activeTracking,
            objects = cameraIndicators(camera, activity).filterIsInstance<CameraIndicator.Object>().map { it.label }.toSet(),
            videoReady = ready, mediaFailed = failed, snapshotFailed = snapshot?.failed == true,
        ),
        snapshot = snapshot?.bitmap?.asImageBitmap(),
        video = {
            player?.let {
                NativeMseVideo(
                    it, Modifier.fillMaxSize(),
                    onReady = { ready -> outputReady = ready },
                    onSurface = onSurface,
                    fullResolution = fullResolution,
                )
            }
        },
    )
}
