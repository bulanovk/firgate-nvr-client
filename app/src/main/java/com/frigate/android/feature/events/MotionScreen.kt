package com.frigate.android.feature.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.frigate.android.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.delay

internal class MotionPlaybackState(initialTime: Double) {
    var timestamp by mutableDoubleStateOf(initialTime)
    var cameraId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var rate by mutableIntStateOf(MOTION_DEFAULT_SPEED)
    var motionOnly by mutableStateOf(false)
    val regions = MotionRegionSelection("")
}

@Composable
internal fun MotionScreen(playback: MotionPlaybackState, query: EventsQuery, points: List<MotionPoint>, reviews: List<FrigateReview>,
    previews: List<ReviewPreview>, profile: ServerProfile, cookie: String, repository: EventsRepository,
    onUnauthorized: () -> Unit, onOpen: (String, Double, Boolean) -> Unit,
    cameraAspects: Map<String, Double> = emptyMap()) {
    val owner = LocalContext.current as LifecycleOwner
    var started by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val camera = playback.cameraId
    if (camera != null) {
        MotionPreviewsPane(camera, query, points, reviews, previews, profile, cookie, repository, onUnauthorized,
            onBack = { playback.cameraId = null }, onOpen = { onOpen(camera, it, false) },
            sourceAspect = cameraAspects[camera] ?: (16.0 / 9), regionSelection = playback.regions)
        return
    }
    LaunchedEffect(playback, query, points, reviews, playback.playing, playback.rate, playback.motionOnly, started) {
        if (!started || !playback.playing) return@LaunchedEffect
        while (true) {
            delay((500L / playback.rate).coerceAtLeast(1))
            val next = nextMotionTimestamp(playback.timestamp, query, points, reviews, playback.motionOnly, System.currentTimeMillis() / 1000.0)
            if (next == null) { playback.playing = false; break }
            playback.timestamp = next
        }
    }
    val grid = rememberLazyGridState()
    val visible by remember(query.cameraIds, grid) { derivedStateOf { grid.layoutInfo.visibleItemsInfo.mapNotNull { query.cameraIds.getOrNull(it.index) }.take(4).toSet() } }
    var speedMenu by remember { mutableStateOf(false) }
    val formatter = remember(query.timezone) { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of(query.timezone)) }
    val stamp = formatter.format(Instant.ofEpochSecond(playback.timestamp.toLong()))
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(if (maxWidth >= 600.dp) 3 else 1), state = grid,
                contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(query.cameraIds, key = { it }) { id ->
                    Box(Modifier.fillMaxWidth().aspectRatio((cameraAspects[id] ?: (16.0 / 9)).toFloat())
                        .semantics { contentDescription = "Motion camera: $id" }
                        .clickable { onOpen(id, playback.timestamp, true) }) {
                        MotionPicture(id, playback.timestamp, query.range, previews, profile, cookie, repository,
                            active = id in visible, playing = playback.playing, rate = playback.rate.toFloat(), onUnauthorized = onUnauthorized)
                        Text(id, color = Color.White, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(Color(0xDB25262B), RoundedCornerShape(4.dp)).padding(5.dp))
                        Text(stamp, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(4.dp)
                                .semantics { contentDescription = "Motion timestamp: $stamp" })
                        IconButton(onClick = { playback.cameraId = id }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp))) {
                            Icon(Icons.Outlined.MoreVert, "Motion clips: $id", tint = Color.White)
                        }
                    }
                }
            }
        }
        Slider(value = (playback.timestamp.coerceIn(query.range.after, query.range.before) - query.range.after).toFloat(),
            onValueChange = { playback.timestamp = query.range.after + it.toDouble() }, valueRange = 0f..(query.range.before - query.range.after).toFloat(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Motion time" })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            EventsButton(if (playback.playing) "Pause motion" else "Play motion", if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                { if (playback.timestamp >= query.range.before - .5) playback.timestamp = query.range.after; playback.playing = !playback.playing })
            Box {
                EventsButton("Motion speed", Icons.Outlined.Speed, { speedMenu = true }, "${playback.rate}x")
                DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                    MOTION_SPEEDS.forEach { rate -> DropdownMenuItem(text = { Text("${rate}x") }, onClick = { playback.rate = rate; speedMenu = false }) }
                }
            }
        }
    }
}

@Composable
internal fun MotionPicture(camera: String, timestamp: Double, range: EventsRange, previews: List<ReviewPreview>, profile: ServerProfile, cookie: String,
    repository: EventsRepository, active: Boolean, playing: Boolean, rate: Float, onUnauthorized: () -> Unit) {
    val source = selectPreview(previews, camera, EventsRange(timestamp, timestamp + .5, range.dayKey))
    var status by remember(source, active) { mutableStateOf(PreviewPlaybackStatus()) }
    var frames by remember(camera) { mutableStateOf<List<PreviewFrame>>(emptyList()) }
    val unauthorized by rememberUpdatedState(onUnauthorized)
    LaunchedEffect(camera, source, active, range) {
        if (active && source == null && usesPreviewFrames(timestamp, System.currentTimeMillis() / 1000.0)) {
            try { frames = repository.previewFrames(camera, range) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { if (error is com.frigate.android.core.server.FrigateHttpException && error.code == 401) unauthorized() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (source != null && active) ReviewPreviewPlayer(profile, cookie, source,
            EventsRange(source.startTime, source.endTime, null), timestamp, playing, rate, onUnauthorized, { status = it })
        val frame = previewFrameCandidates(frames, timestamp).firstOrNull()
        if ((!status.ready || source == null) && frame != null) EventsImage(profile, cookie, frame.src, "Motion frame", onUnauthorized)
        if (source == null && frame == null || status.error != null) Text("Preview unavailable", Modifier.align(Alignment.Center))
        else if (active && !status.ready && frame == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
    }
}
