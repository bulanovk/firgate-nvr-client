package com.frigate.android.feature.history

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.frigate.android.ui.icons.CameraAlt
import com.frigate.android.ui.icons.Tune
import com.frigate.android.ui.icons.Videocam
import com.frigate.android.ui.icons.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.frigate.android.core.server.FrigateCamera
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.RecordingCoverage
import com.frigate.android.core.server.ReviewMediaEndpoint
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.core.server.TimelineSpan
import com.frigate.android.core.server.UnavailableSpan
import com.frigate.android.feature.player.saveFrameToGallery
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal object HistoryColors {
    val background = Color.Black
    val panel = Color(0xFF262626)
    val panelSurface = Color(0xFF1F1F1F)
    val foreground = Color(0xFFE8E8E8)
    val muted = Color(0xFFA3A3A3)
    val line = Color(0xFF414141)
    val alert = Color(0xFF991B1B)
    val detection = Color(0xFFEA580C)
    val motion = Color(0xFFF7B908)
    val motionIdle = Color(0xFF888888)
    val cursor = Color(0xFFEF4444)
    val handle = Color(0xFF991B1B)
}

/**
 * The color scheme of the popups of the archive.
 *
 * The app has no Material theme of its own, so a menu of Material3 would be drawn with the
 * default light scheme and the light text of the archive would be unreadable on it.
 */
internal val historyMenuColorScheme = darkColorScheme(
    surface = HistoryColors.panelSurface,
    surfaceContainer = HistoryColors.panelSurface,
    surfaceContainerHigh = HistoryColors.panel,
    surfaceContainerLow = HistoryColors.panelSurface,
    surfaceContainerLowest = HistoryColors.panelSurface,
    surfaceContainerHighest = HistoryColors.panel,
    onSurface = HistoryColors.foreground,
    onSurfaceVariant = HistoryColors.muted,
    outline = HistoryColors.line,
    outlineVariant = HistoryColors.line,
)

/** The archive of one camera: the reviews, the motion activity and the recording coverage. */
internal sealed interface HistoryArchiveState {
    data object Loading : HistoryArchiveState

    data class Ready(
        val reviews: List<FrigateReview>,
        val motion: List<MotionPoint>,
        val unavailable: List<UnavailableSpan>,
        val coverage: RecordingCoverage,
    ) : HistoryArchiveState

    data class Failed(val message: String) : HistoryArchiveState
}

/**
 * The archive of a camera, structured like the PWA recording view.
 *
 * The playhead is kept in wall-clock time, so switching the camera keeps the moment of the day and
 * only the recorded content changes. The playlist covers the hour chunk of the anchor and every
 * explicit seek moves the anchor: a seek inside the chunk moves the player inside the manifest that
 * is already loaded, a seek over the hour border opens the next chunk.
 *
 * The camera, the panel, the range and the filter belong to the viewing session: the drawers of
 * the archive report their choice to [navigation], and the playhead of the session is kept there
 * as well, so it survives the closing of the screen.
 */
@UnstableApi
@Composable
internal fun HistoryScreen(
    profile: ServerProfile,
    cookie: String,
    cameras: List<FrigateCamera>,
    navigation: HistoryNavigation,
    onBack: () -> Unit,
    onLive: (String) -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier,
    backDescription: String = "Back to Live",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dataSource = remember(profile, cookie) { HistoryDataSource(FrigateClient(profile, cookie)) }
    val zone = remember { ZoneId.systemDefault() }

    var fullscreen by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf<HistoryDrawer>(HistoryDrawer.None) }
    var settingsPage by remember { mutableStateOf<HistorySettingsPage>(HistorySettingsPage.Menu) }
    // The system back leaves the archive the same way the button of the header does.
    BackHandler(onBack = onBack)

    val camera = navigation.cameraId.orEmpty()
    val cameraName = cameras.firstOrNull { it.id == camera }?.name?.takeIf { it.isNotBlank() } ?: camera
    val panelMode = navigation.panelMode
    val range = navigation.range
    val initialRange = navigation.initialRange
    val filter = navigation.filter
    val screenModel = navigation.screenModel
    val controller = navigation.player
    val zoom = screenModel.zoom

    val chunks = remember(range) { chunkedRange(range) }
    // The anchor is clamped to the window of the session right away: a new window must not ask the
    // vod endpoint for a chunk of the window that was open before.
    val anchorSeconds = screenModel.anchorSeconds.coerceIn(range.after, range.before)

    LaunchedEffect(range) {
        screenModel.bounds = range.after..range.before
        controller.bounds = range.after..range.before
    }

    var archive by remember { mutableStateOf<HistoryArchiveState>(HistoryArchiveState.Loading) }
    var archiveRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(camera, range.after, range.before, zoom.segmentDuration, archiveRetry) {
        archive = HistoryArchiveState.Loading
        try {
            archive = HistoryArchiveState.Ready(
                reviews = dataSource.reviews(camera, range),
                motion = dataSource.motion(camera, range, zoom.segmentDuration),
                unavailable = dataSource.unavailable(camera, range, zoom.segmentDuration),
                coverage = dataSource.coverage(camera, range, zoom.segmentDuration),
            )
        } catch (error: Throwable) {
            if (error is FrigateHttpException && error.code == 401) onUnauthorized()
            archive = historyArchiveFailure(error)
        }
    }

    val spans: List<TimelineSpan> = when (val state = archive) {
        is HistoryArchiveState.Ready -> state.coverage.timelines["auto"]
            ?: state.coverage.timelines["main"]
            ?: state.coverage.timelines["sub"]
            ?: emptyList()

        else -> emptyList()
    }

    // The window of the playlist is the hour chunk of the anchor: a seek inside the chunk moves
    // the player inside the manifest that is already loaded.
    val window = remember(chunks, anchorSeconds, spans) {
        historyWindow(chunks, anchorSeconds, spans)
    }
    // The rebuilt source continues at the playhead, an explicit seek lands on the anchor.
    // The seek lands on the anchor. The start of a rebuilt source is read when the source is
    // built, from the playhead of that moment: a step of the playhead must not recompose the screen.
    val seekPosition = window?.let { historySeekPositionInWindow(anchorSeconds, it) }
    val startPosition: () -> Double? = { window?.let { historyStartPosition(controller.positionSeconds, it) } }
    // The playlist is only opened once the archive of the camera is known: the seek of the player
    // is calculated from the realized timelines of the coverage.
    val playlistUrl = if (archive is HistoryArchiveState.Ready && window != null) {
        ReviewMediaEndpoint.vodPlaylistUrl(profile, camera, window.chunk.after, window.chunk.before)
    } else {
        null
    }

    // The playhead moves four times a second and recomposes this screen with every step. What the
    // archive, the camera and the filter decide must not be calculated again for every step: the
    // timeline model walks every segment against every event and costs far more than a frame.
    val allCameraReviews: List<FrigateReview> = remember(archive, camera) {
        when (val state = archive) {
            is HistoryArchiveState.Ready -> state.reviews.filter { it.camera == camera }
            else -> emptyList()
        }
    }
    // The filter of the labels narrows the events of the panels and the marks of the timeline.
    val cameraReviews: List<FrigateReview> = remember(allCameraReviews, filter) {
        allCameraReviews.filter(filter::matches)
    }
    val labels: List<String> = remember(allCameraReviews) {
        allCameraReviews.flatMap { it.objectLabels }.distinct().sorted()
    }

    val segments: List<HistorySegment> = remember(archive, camera, cameraReviews, range, zoom.segmentDuration) {
        when (val state = archive) {
            is HistoryArchiveState.Ready -> HistoryTimelineModel.build(
                camera = camera,
                reviews = cameraReviews,
                motion = state.motion,
                unavailable = state.unavailable,
                coverage = state.coverage.spans,
                range = range,
                segmentDuration = zoom.segmentDuration,
            )

            else -> emptyList()
        }
    }

    val seekToAnchor: (Double) -> Unit = { seconds -> navigation.seekTo(seconds) }

    // The PWA continues the playback in the next hour chunk when the recording of the current one
    // ends: the playhead moves to the beginning of the next chunk and the playback goes on. The
    // last chunk of the window ends the playback with a clear stop.
    val onPlaybackEnded: () -> Unit = {
        val next = nextChunkForPlayback(chunks, anchorSeconds)
        if (next != null && controller.playback.playing) {
            navigation.seekTo(next.after)
            controller.play()
        } else {
            controller.onEnded()
        }
    }

    Box(modifier.fillMaxSize().background(HistoryColors.background)) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                HistoryHeader(
                    cameraName = cameraName,
                    backDescription = backDescription,
                    onBack = onBack,
                    onLive = { onLive(camera) },
                    onOpenCameraPicker = { drawer = HistoryDrawer.Camera },
                    onOpenPanelPicker = { drawer = HistoryDrawer.Panel },
                    onOpenSettings = {
                        settingsPage = HistorySettingsPage.Menu
                        drawer = HistoryDrawer.Settings
                    },
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        HistoryVideoColumn(
                            profile = profile,
                            cookie = cookie,
                            playlistUrl = playlistUrl,
                            startPositionSeconds = startPosition,
                            seekPositionSeconds = seekPosition,
                            seekToken = screenModel.seekToken,
                            spans = window?.spans ?: emptyList(),
                            inpointOffset = window?.inpointOffset ?: 0.0,
                            controller = controller,
                            fullscreen = fullscreen,
                            onFullscreen = { fullscreen = !fullscreen },
                            onSeekBy = { delta -> seekToAnchor(controller.positionSeconds + delta) },
                            onEnded = onPlaybackEnded,
                            onSnapshot = { bitmap -> scope.launch { saveFrame(context, bitmap, snackbar) } },
                            onUnauthorized = onUnauthorized,
                            modifier = Modifier.weight(1f),
                        )
                        HistoryPanelColumn(
                            archive = archive,
                            mode = panelMode,
                            segments = segments,
                            zoom = zoom,
                            position = { controller.positionSeconds },
                            reviews = cameraReviews,
                            profile = profile,
                            cookie = cookie,
                            onSeek = seekToAnchor,
                            onSeekAndPlay = { seconds ->
                                seekToAnchor(seconds)
                                controller.play()
                            },
                            onZoomIn = screenModel::zoomIn,
                            onZoomOut = screenModel::zoomOut,
                            onRetryArchive = { archiveRetry += 1 },
                            modifier = Modifier
                                .width(if (panelMode == HistoryPanelMode.TIMELINE) 100.dp else 304.dp)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        HistoryVideoColumn(
                            profile = profile,
                            cookie = cookie,
                            playlistUrl = playlistUrl,
                            startPositionSeconds = startPosition,
                            seekPositionSeconds = seekPosition,
                            seekToken = screenModel.seekToken,
                            spans = window?.spans ?: emptyList(),
                            inpointOffset = window?.inpointOffset ?: 0.0,
                            controller = controller,
                            fullscreen = fullscreen,
                            onFullscreen = { fullscreen = !fullscreen },
                            onSeekBy = { delta -> seekToAnchor(controller.positionSeconds + delta) },
                            onEnded = onPlaybackEnded,
                            onSnapshot = { bitmap -> scope.launch { saveFrame(context, bitmap, snackbar) } },
                            onUnauthorized = onUnauthorized,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HistoryPanelColumn(
                            archive = archive,
                            mode = panelMode,
                            segments = segments,
                            zoom = zoom,
                            position = { controller.positionSeconds },
                            reviews = cameraReviews,
                            profile = profile,
                            cookie = cookie,
                            onSeek = seekToAnchor,
                            onSeekAndPlay = { seconds ->
                                seekToAnchor(seconds)
                                controller.play()
                            },
                            onZoomIn = screenModel::zoomIn,
                            onZoomOut = screenModel::zoomOut,
                            onRetryArchive = { archiveRetry += 1 },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }

            when (val open = drawer) {
                HistoryDrawer.None -> Unit

                HistoryDrawer.Camera -> HistoryCameraDrawer(
                    cameras = cameras,
                    selected = camera,
                    onSelect = { id ->
                        navigation.changeCamera(id)
                        drawer = HistoryDrawer.None
                    },
                    onDismiss = { drawer = HistoryDrawer.None },
                )

                HistoryDrawer.Panel -> HistoryPanelDrawer(
                    selected = panelMode,
                    onSelect = { mode ->
                        navigation.changePanelMode(mode)
                        drawer = HistoryDrawer.None
                    },
                    onDismiss = { drawer = HistoryDrawer.None },
                )

                HistoryDrawer.Settings -> HistorySettingsDrawer(
                    page = settingsPage,
                    range = range,
                    initialRange = initialRange,
                    zone = zone,
                    filter = filter,
                    labels = labels,
                    onPage = { page -> settingsPage = page },
                    onSelectRange = { next -> navigation.changeRange(next) },
                    onApplyFilter = { next -> navigation.changeFilter(next) },
                    onExport = {
                        drawer = HistoryDrawer.Notice(
                            "Export",
                            "In the PWA the borders of the export are chosen on the timeline. " +
                                "This app does not create files and does not send requests.",
                        )
                    },
                    onShare = {
                        drawer = HistoryDrawer.Notice(
                            "Share moment",
                            "A real Frigate link to the moment of the archive is not created.",
                        )
                    },
                    onMotionSearch = {
                        drawer = HistoryDrawer.Notice(
                            "Motion search",
                            "In the PWA a search by the area of the frame opens. This app does not " +
                                "reproduce that form.",
                        )
                    },
                    onDismiss = { drawer = HistoryDrawer.None },
                )

                is HistoryDrawer.Notice -> HistoryNoticeDrawer(
                    title = open.title,
                    description = open.description,
                    onDismiss = { drawer = HistoryDrawer.None },
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@UnstableApi
@Composable
private fun HistoryVideoColumn(
    profile: ServerProfile,
    cookie: String,
    playlistUrl: String?,
    startPositionSeconds: () -> Double?,
    seekPositionSeconds: Double?,
    seekToken: Int,
    spans: List<TimelineSpan>,
    inpointOffset: Double,
    controller: HistoryPlayerController,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onEnded: () -> Unit,
    onSnapshot: (Bitmap) -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val frameHeight = if (fullscreen) maxHeight else maxHeight * 0.5f
        Box(
            Modifier
                .fillMaxWidth()
                .height(frameHeight),
            contentAlignment = Alignment.Center,
        ) {
            HistoryVideoPlayer(
                profile = profile,
                cookie = cookie,
                playlistUrl = playlistUrl,
                startPositionSeconds = startPositionSeconds,
                seekPositionSeconds = seekPositionSeconds,
                seekToken = seekToken,
                segments = spans,
                inpointOffset = inpointOffset,
                controller = controller,
                onSeekBy = onSeekBy,
                onEnded = onEnded,
                onError = { error ->
                    if (error == HistoryError.Unauthorized) onUnauthorized()
                    controller.onError(error)
                },
                onSnapshot = onSnapshot,
                onFullscreen = onFullscreen,
                fullscreen = fullscreen,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HistoryPanelColumn(
    archive: HistoryArchiveState,
    mode: HistoryPanelMode,
    segments: List<HistorySegment>,
    zoom: TimelineZoom,
    // Read inside the panel only: the playhead moves four times a second and must not
    // recompose the whole screen.
    position: () -> Double,
    reviews: List<FrigateReview>,
    profile: ServerProfile,
    cookie: String,
    onSeek: (Double) -> Unit,
    onSeekAndPlay: (Double) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRetryArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(HistoryColors.panel),
    ) {
        when (archive) {
            is HistoryArchiveState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HistoryColors.muted, strokeWidth = 2.dp)
            }

            is HistoryArchiveState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        archive.message,
                        color = HistoryColors.foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetryArchive, modifier = Modifier.height(48.dp)) {
                        Text("Retry", color = HistoryColors.foreground)
                    }
                }
            }

            is HistoryArchiveState.Ready -> when (mode) {
                HistoryPanelMode.TIMELINE -> HistoryTimelinePanel(
                    segments = segments,
                    zoom = zoom,
                    position = position,
                    onSeek = onSeek,
                    onZoomIn = onZoomIn,
                    onZoomOut = onZoomOut,
                    canZoomIn = zoom != HISTORY_ZOOM_LEVELS.last(),
                    canZoomOut = zoom != HISTORY_ZOOM_LEVELS.first(),
                )

                HistoryPanelMode.EVENTS -> HistoryEventsPanel(
                    reviews = reviews,
                    profile = profile,
                    cookie = cookie,
                    positionSeconds = position(),
                    onSeekAndPlay = onSeekAndPlay,
                )

                HistoryPanelMode.DETAIL -> HistoryDetailPanel(
                    reviews = reviews,
                    positionSeconds = position(),
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    cameraName: String,
    backDescription: String,
    onBack: () -> Unit,
    onLive: () -> Unit,
    onOpenCameraPicker: () -> Unit,
    onOpenPanelPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backDescription, tint = HistoryColors.foreground)
        }
        IconButton(onClick = onLive, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Videocam, "Live of $cameraName", tint = HistoryColors.foreground)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "FRIGATE",
                color = HistoryColors.foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                cameraName,
                color = HistoryColors.muted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = onOpenCameraPicker, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.CameraAlt, "Choose a camera", tint = HistoryColors.foreground)
        }
        IconButton(onClick = onOpenPanelPicker, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.ViewAgenda, "Change the panel", tint = HistoryColors.foreground)
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Tune, "Viewing settings", tint = HistoryColors.foreground)
        }
    }
}

private fun historyArchiveMessage(error: Throwable): String = when (error) {
    is FrigateHttpException -> "Frigate returned HTTP ${error.code}."
    else -> error.message?.takeIf(String::isNotBlank) ?: "The archive could not be loaded."
}

/**
 * The failure of an archive load.
 *
 * A cancelled load is not a failure of the archive: the newer load owns the state now, so the
 * cancellation is passed on instead of writing an error of the previous camera into the panel.
 */
internal fun historyArchiveFailure(error: Throwable): HistoryArchiveState.Failed {
    if (error is CancellationException) throw error
    return HistoryArchiveState.Failed(historyArchiveMessage(error))
}

private suspend fun saveFrame(context: Context, bitmap: Bitmap, snackbar: SnackbarHostState) {
    val saved = saveFrameToGallery(context, bitmap)
    snackbar.showSnackbar(if (saved) "Frame saved to Pictures/Frigate" else "Could not save the frame")
}
