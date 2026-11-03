package com.frigate.android.feature.events

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ReviewMediaEndpoint
import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun ReviewCardMedia(profile: ServerProfile, cookie: String, repository: EventsRepository, review: FrigateReview,
    active: Boolean, onUnauthorized: () -> Unit, onClose: () -> Unit, onViewed: () -> Unit = {}) {
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val viewed by rememberUpdatedState(onViewed)
    val range = remember(review.id, active) {
        EventsRange(review.startTime - 32, maxOf(review.startTime + .001, review.endTime ?: System.currentTimeMillis() / 1000.0), null)
    }
    var source by remember(profile, cookie, review.id, active) { mutableStateOf<ReviewPreview?>(null) }
    var loaded by remember(profile, cookie, review.id, active) { mutableStateOf(false) }
    var failed by remember(profile, cookie, review.id, active) { mutableStateOf(false) }
    var status by remember(profile, cookie, review.id, active) { mutableStateOf(PreviewPlaybackStatus()) }
    var frame by remember(profile, cookie, review.id, active) { mutableStateOf<Bitmap?>(null) }
    var seek by remember(review.id, active) { mutableStateOf<Double?>(null) }
    var framePosition by remember(review.id, active) { mutableDoubleStateOf(range.after) }
    val imageTransport = remember(profile, cookie) { EventsImageTransport(profile, cookie) }
    LaunchedEffect(profile, cookie, review.id, active) {
        if (!active) return@LaunchedEffect
        try {
            source = selectPreview(repository.previews(listOf(review.camera), range), review.camera, range)
            loaded = true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            loaded = true; failed = true
            if (error is FrigateHttpException && error.code == 401) unauthorized()
        }
    }
    var marked by remember(review.id, active) { mutableStateOf(false) }
    val fallback = active && loaded && (source == null || status.error != null) && usesPreviewFrames(review.endTime ?: range.before, System.currentTimeMillis() / 1000.0)
    LaunchedEffect(profile, cookie, review.id, fallback, seek) {
        if (!fallback) return@LaunchedEffect
        try {
            val frames = repository.previewFrames(review.camera, range)
            if (frames.isEmpty()) { failed = true; return@LaunchedEffect }
            var clock = seek ?: range.after
            while (true) {
                var decoded: Bitmap? = null
                for (candidate in previewFrameCandidates(frames, clock)) {
                    try { decoded = decodeEventImage(imageTransport, candidate.src); clock = candidate.timestamp; break }
                    catch (error: FrigateHttpException) { if (error.code == 401) throw error }
                    catch (_: IOException) { }
                }
                if (decoded == null) { failed = true; break }
                frame = decoded
                framePosition = clock
                if (!marked && clock >= range.after + (range.before - range.after) / 2) { marked = true; viewed() }
                delay(125)
                clock += 1
                if (clock >= minOf(range.before, frames.last().timestamp + 1)) clock = range.after
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            failed = true
            if (error is FrigateHttpException && error.code == 401) unauthorized()
        }
    }
    LaunchedEffect(status.positionSeconds, status.ready) {
        if (active && status.ready && !marked && status.positionSeconds >= range.after + (range.before - range.after) / 2) {
            marked = true; viewed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (!active || !status.ready && frame == null) EventsImage(profile, cookie,
            ReviewMediaEndpoint.thumbnailUrl(profile, review.thumbnailPath), null, onUnauthorized)
        if (active) {
            source?.takeIf { status.error == null }?.let { video ->
                ReviewPreviewPlayer(profile, cookie, video, range, timestamp = seek,
                    onUnauthorized = onUnauthorized, onStatus = { status = it })
            }
            frame?.let { Image(it.asImageBitmap(), "Event preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (failed || loaded && source == null && !fallback || status.error != null && !fallback) {
                Text("Preview unavailable", modifier = Modifier.align(Alignment.Center))
            } else if (!status.ready && frame == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "Stop preview") }
            if (status.ready || frame != null) Slider(
                value = ((if (fallback) framePosition else status.positionSeconds).coerceIn(range.after, range.before) - range.after).toFloat(),
                valueRange = 0f..(range.before - range.after).toFloat(),
                onValueChange = { seek = range.after + it },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp),
            )
        }
    }
}
