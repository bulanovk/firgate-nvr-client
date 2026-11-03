package com.frigate.android.feature.events

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.frigate.android.core.server.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun EventsFeature(session: EventsSessionHandle, interaction: EventsInteraction, profile: ServerProfile, cookie: String,
    catalog: EventsCatalog, grid: LazyGridState, playback: MotionPlaybackState, onUnauthorized: () -> Unit,
    onOpen: (ReviewOpenRequest) -> Unit, onMotion: (String, Double, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    val preview by interaction.previewId.collectAsState()
    val deleting by interaction.deleteRequested.collectAsState()
    val exporting by interaction.exportRequested.collectAsState()
    var cases by remember(session) { mutableStateOf<List<ExportCase>>(emptyList()) }
    LaunchedEffect(exporting) {
        if (exporting && catalog.role == "admin" && interaction.exportCount > 1) {
            try { cases = session.repository.exportCases() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                session.controller.showMessage("Could not load cases. You can export without a case.")
                if (error is FrigateHttpException && error.code == 401) onUnauthorized()
            }
        }
    }
    if (deleting && catalog.role == "admin") EventsDeleteDialog(interaction.deleteCount,
        interaction::dismissDelete, { interaction.confirmDelete(catalog.role) })
    if (exporting) EventsExportDialog(interaction.exportCount, cases, interaction::dismissExport,
        { interaction.confirmExport(System.currentTimeMillis() / 1000.0, it) }, allowCase = catalog.role == "admin")
    val scope = rememberCoroutineScope()
    val motionCameras = state.query.cameraIds
    val motionDay = state.query.range.dayKey
    val motionTimezone = state.query.timezone
    var points by remember(session, motionCameras, motionDay, motionTimezone) { mutableStateOf<List<MotionPoint>>(emptyList()) }
    var previews by remember(session, motionCameras, motionDay, motionTimezone) { mutableStateOf<List<ReviewPreview>>(emptyList()) }
    var motionLoaded by remember(session, motionCameras, motionDay, motionTimezone) { mutableStateOf(false) }
    var motionLoading by remember(session) { mutableStateOf(false) }
    var motionFailed by remember(session) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val unauthorized by rememberUpdatedState(onUnauthorized)
    LaunchedEffect(session, state.query, state.filter.type, retry) {
        interaction.closePreview()
        if (state.filter.type != EventsType.MOTION) {
            playback.cameraId = null
            return@LaunchedEffect
        }
        motionLoading = true; motionFailed = false
        val query = state.query.copy(labels = emptyList(), zones = emptyList())
        playback.timestamp = playback.timestamp.coerceIn(query.range.after, query.range.before)
        try {
            val nextPoints = session.repository.motion(query)
            val nextPreviews = session.repository.previews(query.cameraIds, query.range)
            points = nextPoints
            previews = nextPreviews
            motionLoaded = true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            motionFailed = true
            if (error is FrigateHttpException && error.code == 401) unauthorized()
        } finally { motionLoading = false }
    }
    val owner = LocalContext.current as LifecycleOwner
    DisposableEffect(interaction, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) interaction.closePreview()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            interaction.closePreview()
        }
    }
    Column(modifier) {
    EventsScreen(state, onOpenReview = {
        interaction.closePreview()
        onOpen(it)
        interaction.dispatch(EventsAction.MarkViewed(listOf(it.review.id), true))
    }, onAction = interaction::dispatch, modifier = Modifier.weight(1f), catalog = catalog, gridState = grid,
        picture = { review -> ReviewCardMedia(profile, cookie, session.repository, review, preview == review.id, onUnauthorized,
            onClose = interaction::closePreview,
            onViewed = { interaction.dispatch(EventsAction.MarkViewed(listOf(review.id), true)) }) },
        timeline = {
            EventsTimeline(EventsTimelineModel(state.query.range, filteredReviews(state.items, state.filter), points), state.query.timezone,
                onReview = { id ->
                    val index = filteredReviews(state.items, state.filter).indexOfFirst { it.id == id }
                    if (index >= 0) scope.launch { grid.animateScrollToItem(index) }
                }, onSeek = { playback.timestamp = it })
        }, motion = {
            Column {
                if (motionFailed) TextButton(onClick = { retry++ }) { Text("Could not load motion. Retry") }
                if (motionLoaded) MotionScreen(playback, state.query, points, state.items, previews, profile, cookie,
                    session.repository, onUnauthorized, onMotion, cameraAspects = catalog.cameraAspects)
                else if (motionLoading) CircularProgressIndicator()
            }
        },
        motionOnly = playback.motionOnly,
        onMotionOnly = { playback.motionOnly = !playback.motionOnly })
    EventsExports(session, interaction, profile, cookie, onUnauthorized)
    }
}
