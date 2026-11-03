package com.frigate.android.feature.live

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.frigate.android.core.server.FrigateCamera
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.player.PlayerZoomState
import com.frigate.android.feature.player.playerZoomGestures
import com.frigate.android.feature.player.playerZoomLabel
import com.frigate.android.feature.player.playerZoomTransform
import com.frigate.android.feature.player.rememberPlayerZoomState
import com.frigate.android.feature.player.saveFrameToGallery
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The panel of a camera: the header of the PWA above a picture that fills the screen.
 *
 * The picture keeps the aspect of the camera and can be enlarged with a pinch: the zoom is a
 * property of the player, the same one the archive uses.
 */
@UnstableApi
@Composable
internal fun LiveCameraPanel(
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
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onMessage: (String) -> Unit = {},
    zoom: PlayerZoomState = rememberPlayerZoomState(),
    role: String = "viewer",
    onSetControl: (CameraControl, Boolean) -> Boolean = { _, _ -> false },
    onStreamChange: (String) -> Unit = {},
    audioAvailable: Boolean = false,
    muted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
    onPictureInPicture: ((Float, android.graphics.Rect) -> Unit)? = null,
    pictureInPicture: Boolean = false,
    onUnauthorized: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var surface by remember { mutableStateOf<MseTextureView?>(null) }
    var pictureBounds by remember { mutableStateOf(Rect()) }
    var settingsOpen by remember(camera.id) { mutableStateOf(false) }
    var showStatistics by remember(camera.id) { mutableStateOf(false) }
    var showDebug by remember(camera.id) { mutableStateOf(false) }
    // A camera that announced audio and delivered nothing has no sound: the button goes away,
    // as in the PWA, where the button follows the capabilities of the camera.
    var audioUnavailable by remember(camera.id) { mutableStateOf(false) }
    val soundAvailable = audioAvailable && !audioUnavailable
    var playbackStats by remember(surface) { mutableStateOf<LivePlaybackStats?>(null) }
    LaunchedEffect(surface, showStatistics, showDebug, visible) {
        if (!visible || (!showStatistics && !showDebug)) return@LaunchedEffect
        while (true) {
            playbackStats = surface?.playbackStats()
            delay(1_000)
        }
    }
    LaunchedEffect(pictureInPicture) { if (pictureInPicture) settingsOpen = false }
    val pending = remember(camera.id) { LiveCameraControlRequests() }
    val client = remember(profile, cookie) { FrigateClient(profile, cookie) }
    val recordingSession = remember(profile, cookie, camera.id, visible) {
        // This owner survives disposal long enough to end an in-flight manual event.
        ManualRecordingSession(CoroutineScope(Dispatchers.Main.immediate),
            start = {
                try {
                    withContext(Dispatchers.IO) { client.startManualRecording(camera.id) }
                } catch (error: FrigateHttpException) {
                    // An expired session is renewed by the app, the recording is not the reason.
                    if (error.code == 401) onUnauthorized()
                    throw error
                }
            },
            end = { id ->
                try {
                    withContext(Dispatchers.IO) { client.endManualRecording(id) }
                } catch (error: FrigateHttpException) {
                    if (error.code == 401) onUnauthorized()
                    throw error
                }
            },
            onError = onMessage)
    }
    DisposableEffect(recordingSession) { onDispose { recordingSession.close() } }
    val recordingId by recordingSession.eventId.collectAsState()
    val recordingBusy by recordingSession.busy.collectAsState()
    // The manual recording is an admin action: a viewer gets no button for it, as in the PWA.
    val recordingAction: (() -> Unit)? = if (role == "admin") {
        { recordingSession.toggle() }
    } else {
        null
    }
    LaunchedEffect(activity, pending.pendingControls()) {
        pending.pendingControls().forEach { control ->
            val expected = pending.expectedValue(control) ?: return@forEach
            if (activity.controlValue(control, camera) == expected) pending.confirm(control, expected)
        }
    }
    val setControl: (CameraControl, Boolean) -> Unit = { control, value ->
        if (control in cameraControls(camera, role) && !pending.isPending(control)) {
            if (onSetControl(control, value)) {
                val token = pending.begin(control, value)
                scope.launch {
                    delay(5_000)
                    // Only the timeout of this very request may report a failure: an old timeout
                    // must not cancel a newer request of the same control.
                    if (pending.finish(control, token)) {
                        onMessage("Camera did not confirm ${control.label.lowercase()}")
                    }
                }
            } else onMessage("Camera connection is unavailable")
        }
    }

    val stream = LiveCameraStream(
        camera, profile, cookie, activity, visible, keepPlayer, playerKey, codecOffer, httpClient,
        pool, statuses, aspects, videoWidths, snapshots,
        manual = true, fullResolution = true, onSurface = { surface = it },
        audioMuted = muted,
        onAudioUnavailable = {
            // The camera announced audio and delivered nothing: the sound goes off again and the
            // user learns the truth instead of a promise.
            audioUnavailable = true
            onMutedChange(true)
            onMessage("This camera has no audio")
        },
    )
    // The limit of the zoom follows the resolution of the stream: a 4K camera can be enlarged ten
    // times, a 360p camera cannot be enlarged at all.
    val sourceWidth = videoWidths[playerKey] ?: 0
    LaunchedEffect(sourceWidth) { zoom.onSourceSize(sourceWidth) }
    // Another camera means another frame: the zoom of the previous one does not fit it.
    LaunchedEffect(camera.id) { zoom.reset() }

    // The aspect of the picture is the aspect of the decoded frame; the detection resolution is
    // the fallback while the first frame is still on its way.
    val cameraAspect = aspects[playerKey]
        ?: if (camera.detectWidth > 0 && camera.detectHeight > 0) {
            camera.detectWidth.toFloat() / camera.detectHeight
        } else {
            stream.visual.pictureAspect
        }
    val takeSnapshot = {
        scope.launch { saveCurrentFrame(context, surface, onMessage) }
        settingsOpen = false
    }

    val settingsContent: @Composable () -> Unit = {
        LiveCameraSettingsContent(camera, activity, role, pending.pendingControls(), setControl, onStreamChange,
            takeSnapshot, recordingId != null, recordingBusy, { recordingSession.toggle() },
            showStatistics, { showStatistics = it }, showDebug, { showDebug = it })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen && !pictureInPicture) {
                LiveCameraHeader(
                    expanded = expanded,
                    floating = false,
                    settingsOpen = settingsOpen,
                    onSettingsChange = { settingsOpen = it },
                    onBack = onBack,
                    onOpenHistory = onOpenHistory,
                    onFullscreen = { onFullscreenChange(true) },
                    onSnapshot = takeSnapshot,
                    settingsContent = settingsContent,
                    audioAvailable = soundAvailable,
                    muted = muted,
                    onMutedChange = onMutedChange,
                    onPictureInPicture = onPictureInPicture?.let { enter -> { enter(cameraAspect, pictureBounds) } },
                    recording = recordingId != null,
                    recordingBusy = recordingBusy,
                    onRecording = recordingAction,
                )
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiveCameraTile(
                    stream.visual.copy(
                        tileAspect = cameraAspect,
                        pictureAspect = cameraAspect,
                        showName = false,
                        rounded = false,
                    ),
                    stream.snapshot,
                    modifier = Modifier
                        // The zoom is announced: a screen reader has to say how far the picture is
                        // enlarged, and the test has to see that the fingers reached the picture.
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            pictureBounds = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                                bounds.right.roundToInt(), bounds.bottom.roundToInt())
                        }
                        .semantics { contentDescription = "Zoom ${playerZoomLabel(zoom.zoom.scale)}" }
                        .playerZoomGestures(zoom)
                        .playerZoomTransform(zoom),
                    onRetry = onRetry,
                ) {
                    stream.video()
                }
                if (!pictureInPicture && (showStatistics || showDebug)) {
                    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color(0xCC17181B), RoundedCornerShape(8.dp)).padding(8.dp)) {
                        if (showStatistics) {
                            Text(playbackStats?.summary() ?: "Waiting for playback",
                                color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.semantics { contentDescription = "Playback statistics" })
                        }
                        if (showDebug) {
                            Text(playbackStats?.debugSummary() ?: "MSE · waiting for first frame",
                                color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.semantics { contentDescription = "Playback debug view" })
                        }
                    }
                }
            }
        }
        if (fullscreen && !pictureInPicture) {
            // The header of the fullscreen is a floating group in the top right corner, as in the
            // PWA: the picture keeps the whole screen and the buttons hover over it.
            LiveCameraHeader(
                expanded = expanded,
                floating = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
                settingsOpen = settingsOpen,
                onSettingsChange = { settingsOpen = it },
                onBack = { onFullscreenChange(false) },
                onOpenHistory = onOpenHistory,
                onFullscreen = { onFullscreenChange(false) },
                onSnapshot = takeSnapshot,
                settingsContent = settingsContent,
                audioAvailable = soundAvailable,
                muted = muted,
                onMutedChange = onMutedChange,
                onPictureInPicture = onPictureInPicture?.let { enter -> { enter(cameraAspect, pictureBounds) } },
                recording = recordingId != null,
                recordingBusy = recordingBusy,
                onRecording = recordingAction,
            )
        }
    }

    // The settings of the camera: a sheet from below on a narrow screen, a menu on a wide one.
    if (settingsOpen && !expanded) {
        LiveCameraSettingsSheet(onDismiss = { settingsOpen = false }, content = settingsContent)
    }
}

/**
 * The header of the camera: two layouts, as in the PWA, where the width decides.
 *
 * The fullscreen keeps a floating group in the top right corner with a back button: without it
 * there is no way out of the fullscreen on a phone.
 */
@Composable
private fun LiveCameraHeader(
    expanded: Boolean,
    floating: Boolean,
    settingsOpen: Boolean,
    onSettingsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onFullscreen: () -> Unit,
    onSnapshot: () -> Unit,
    settingsContent: @Composable () -> Unit,
    audioAvailable: Boolean,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onPictureInPicture: (() -> Unit)?,
    recording: Boolean,
    recordingBusy: Boolean,
    onRecording: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (floating) {
        Row(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC17181B))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveCameraHeaderButton(
                LiveCameraHeaderIcons.back, "Back", expanded, "Back", onBack,
                filled = true,
            )
            LiveCameraHeaderButton(
                LiveCameraHeaderIcons.compress, "Leave fullscreen", expanded, null, onFullscreen,
            )
            if (onPictureInPicture != null) {
                LiveCameraHeaderButton(LiveCameraHeaderIcons.pip, "Picture in picture", expanded, null, onPictureInPicture)
            }
            if (audioAvailable) {
                LiveCameraHeaderButton(
                    if (muted) LiveCameraHeaderIcons.muted else LiveCameraHeaderIcons.audio,
                    if (muted) "Unmute audio" else "Mute audio", expanded, null,
                    { onMutedChange(!muted) },
                )
            }
            if (expanded && onRecording != null) {
                LiveCameraHeaderButton(
                    LiveCameraHeaderIcons.record,
                    if (recording) "Stop recording" else "Start recording",
                    expanded, null, onRecording,
                    enabled = !recordingBusy,
                    active = recording,
                )
            }
            if (expanded) {
                LiveCameraHeaderButton(
                    LiveCameraHeaderIcons.snapshot, "Take a snapshot", expanded, null, onSnapshot,
                )
            }
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveCameraHeaderButton(
                LiveCameraHeaderIcons.back, "Back to cameras", expanded, "Back", onBack,
                ghost = true,
            )
            LiveCameraHeaderButton(
                LiveCameraHeaderIcons.history, "Open history", expanded, "History", onOpenHistory,
                ghost = true,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveCameraHeaderButton(
                LiveCameraHeaderIcons.fullscreen, "Fullscreen", expanded, null, onFullscreen,
            )
            if (onPictureInPicture != null) {
                LiveCameraHeaderButton(LiveCameraHeaderIcons.pip, "Picture in picture", expanded, null, onPictureInPicture)
            }
            if (audioAvailable) {
                LiveCameraHeaderButton(
                    if (muted) LiveCameraHeaderIcons.muted else LiveCameraHeaderIcons.audio,
                    if (muted) "Unmute audio" else "Mute audio", expanded, null,
                    { onMutedChange(!muted) },
                )
            }
            if (expanded && onRecording != null) {
                LiveCameraHeaderButton(
                    LiveCameraHeaderIcons.record,
                    if (recording) "Stop recording" else "Start recording",
                    expanded, null, onRecording,
                    enabled = !recordingBusy,
                    active = recording,
                )
            }
            if (expanded) {
                LiveCameraHeaderButton(
                    LiveCameraHeaderIcons.snapshot, "Take a snapshot", expanded, null, onSnapshot,
                )
            }
            LiveCameraSettingsButton(
                expanded = expanded,
                settingsOpen = settingsOpen,
                onSettingsChange = onSettingsChange,
                content = settingsContent,
            )
        }
    }
}

/**
 * The cog of the header.
 *
 * The settings disappear in fullscreen; on a wide screen they open a dropdown menu.
 */
@Composable
private fun LiveCameraSettingsButton(
    expanded: Boolean,
    settingsOpen: Boolean,
    onSettingsChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Box {
        LiveCameraHeaderButton(
            LiveCameraHeaderIcons.settings, "Camera settings", expanded, null,
            { onSettingsChange(!settingsOpen) },
        )
        if (expanded) {
            MaterialTheme(colorScheme = darkColorScheme(surface = Color(0xFF17181B))) {
                DropdownMenu(expanded = settingsOpen, onDismissRequest = { onSettingsChange(false) },
                    modifier = Modifier.width(320.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun LiveCameraHeaderButton(
    icon: ImageVector,
    description: String,
    expanded: Boolean,
    label: String?,
    onClick: () -> Unit,
    ghost: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        filled -> Modifier.background(
            Brush.linearGradient(listOf(Color(0xFF6B7280), Color(0xFF4B5563))), shape,
        )
        ghost -> Modifier
        else -> Modifier.background(Color(0xFF26282D), shape)
    }
    val tint = when {
        !enabled -> Color(0xFFE9E9EB).copy(alpha = .45f)
        active -> Color(0xFFFB923C)
        else -> Color(0xFFE9E9EB)
    }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 36.dp)
            .clip(shape)
            .then(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = if (expanded && label != null) 10.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        if (expanded && label != null) {
            Text(label, color = tint, fontSize = 13.sp)
        }
    }
}

/** The same settings content is used by the narrow drawer and the wide menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCameraSettingsSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val height = LocalConfiguration.current.screenHeightDp.dp * .72f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF17181B),
    ) {
        Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("CAMERA SETTINGS", color = Color(0xFF8B8F96), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            content()
        }
    }
}

/** Saves the current frame of the camera into the gallery. */
private suspend fun saveCurrentFrame(
    context: Context,
    surface: MseTextureView?,
    onMessage: (String) -> Unit,
) {
    val bitmap = surface?.currentBitmap()
    if (bitmap == null) {
        onMessage("No frame to save")
        return
    }
    val saved = saveFrameToGallery(context, bitmap)
    onMessage(if (saved) "Frame saved to Pictures/Frigate" else "Could not save the frame")
}
