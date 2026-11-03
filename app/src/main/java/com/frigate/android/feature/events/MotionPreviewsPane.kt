package com.frigate.android.feature.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import com.frigate.android.ui.icons.GridOn
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.frigate.android.core.server.FrigateHttpException
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.ServerProfile

@Composable
internal fun MotionPreviewsPane(camera: String, query: EventsQuery, points: List<MotionPoint>, reviews: List<FrigateReview>,
    previews: List<ReviewPreview>, profile: ServerProfile, cookie: String, repository: EventsRepository,
    onUnauthorized: () -> Unit, onBack: () -> Unit, onOpen: (Double) -> Unit, sourceAspect: Double = 16.0 / 9,
    regionSelection: MotionRegionSelection = remember(camera) { MotionRegionSelection(camera) }) {
    BackHandler(onBack = onBack)
    val selection = remember(camera, regionSelection) { regionSelection.apply { changeCamera(camera) } }
    var settings by remember(camera) { mutableStateOf(false) }
    var revision by remember(camera) { mutableIntStateOf(0) }
    var recordings by remember(camera, query.range) { mutableStateOf<List<MotionRecording>>(emptyList()) }
    var recordingsFailed by remember(camera, query.range) { mutableStateOf(false) }
    var retry by remember(camera) { mutableIntStateOf(0) }
    LaunchedEffect(camera, query.range, retry) {
        recordingsFailed = false
        try { recordings = repository.recordings(camera, query.range) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            recordingsFailed = true
            if (error is FrigateHttpException && error.code == 401) onUnauthorized()
        }
    }
    val allClips = remember(camera, points, reviews) { motionOnlyRanges(points, reviews, camera, System.currentTimeMillis() / 1000.0) }
    val clips = remember(allClips, recordings, revision) {
        if (recordings.none { it.heatmap.isNotEmpty() }) allClips else allClips.filter { matchesRegion(mergedHeatmap(recordings, it), selection.applied) }
    }
    if (settings) MotionRegionSheet(selection, mergedHeatmap(recordings, query.range),
        onDismiss = { settings = false }, onApply = { revision++; settings = false }, sourceAspect = sourceAspect.toFloat(),
        background = { EventsImage(profile, cookie, "api/$camera/latest.jpg", "Region camera background", onUnauthorized, height = 500) })
    val grid = rememberLazyGridState()
    val visible by remember(grid) { derivedStateOf { grid.layoutInfo.visibleItemsInfo.map { it.index }.take(4).toSet() } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EventsButton("Back to motion cameras", Icons.Outlined.ArrowBack, onBack)
            Text(camera)
            Spacer(Modifier.weight(1f))
            EventsButton("Motion regions", Icons.Outlined.GridOn, { settings = true })
        }
        if (recordingsFailed) TextButton(onClick = { retry++ }) { Text("Could not load heatmap. Retry") }
        if (clips.isEmpty()) Text("No motion clips", Modifier.padding(16.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(if (maxWidth >= 600.dp) 3 else 1), state = grid,
                contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips, key = { _, clip -> clip.after }) { index, clip ->
                    Box(Modifier.fillMaxWidth().clickable { onOpen(clip.after) }) {
                        val source = selectPreview(previews, camera, clip)
                        MotionRegionFrame(sourceAspect, if (selection.crop) regionBounds(selection.applied) else null,
                            mergedHeatmap(recordings, clip), selection.dimPercent) {
                            if (source != null && index in visible) ReviewPreviewPlayer(profile, cookie, source, clip, onUnauthorized = onUnauthorized)
                            else Text(if (source == null) "Preview unavailable" else "Motion clip")
                        }
                        Text(java.time.Instant.ofEpochSecond(clip.after.toLong()).atZone(java.time.ZoneId.of(query.timezone))
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                }
            }
        }
    }
}
