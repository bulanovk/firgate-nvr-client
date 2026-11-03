package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.VolumeUp
import com.frigate.android.feature.live.objectIcon
import com.frigate.android.feature.review.reviewTimeAgo
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigate.android.core.server.FrigateReview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal fun reviewTime(review: FrigateReview, timezone: String): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of(timezone)).format(Instant.ofEpochSecond(review.startTime.toLong()))

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReviewGridCard(
    review: FrigateReview,
    selected: Boolean,
    pending: Boolean,
    onOpen: () -> Unit,
    onAction: (EventsAction) -> Unit,
    modifier: Modifier = Modifier,
    cameraName: String = review.camera,
    timezone: String = "UTC",
    picture: @Composable BoxScope.() -> Unit = {},
) {
    val action by rememberUpdatedState(onAction)
    val markable = review.endTime != null && !review.hasBeenReviewed
    val severity = if (review.severity == "alert") Color(0xFFEF4444) else Color(0xFFEA580C)
    val stamp = reviewTime(review, timezone)
    val dragged = remember(review.id) { booleanArrayOf(false) }
    Box(modifier.aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF24282B))
        .then(if (selected) Modifier.border(3.dp, severity, RoundedCornerShape(8.dp)) else Modifier)
        .pointerInput(review.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                dragged[0] = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer != null && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged[0] = true
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(review.id, markable, pending) {
            var delta = 0f
            detectHorizontalDragGestures(
                onDragStart = { delta = 0f },
                onDragCancel = { delta = 0f },
                onDragEnd = {
                    if (!pending && abs(delta) >= size.width * .2f) {
                        if (delta > 0) action(EventsAction.Preview(review.id))
                        else if (markable) action(EventsAction.MarkViewed(listOf(review.id), true))
                    }
                },
                onHorizontalDrag = { change, amount -> change.consume(); delta += amount },
            )
        }
        .combinedClickable(enabled = !pending, onClick = { if (!dragged[0]) onOpen() },
            onLongClickLabel = "Select event", onLongClick = { action(EventsAction.ToggleSelection(review.id)) })
        .semantics(mergeDescendants = true) {
            contentDescription = "Open recording: $cameraName, $stamp"
            onClick { if (!pending) onOpen(); !pending }
            this.selected = selected
            stateDescription = if (review.hasBeenReviewed) "Reviewed" else "Not reviewed"
            customActions = buildList {
                add(CustomAccessibilityAction("Preview") { action(EventsAction.Preview(review.id)); true })
                add(CustomAccessibilityAction("Select event") { action(EventsAction.ToggleSelection(review.id)); true })
                if (markable) add(CustomAccessibilityAction("Mark as reviewed") { action(EventsAction.MarkViewed(listOf(review.id), true)); true })
            }
        }) {
        picture()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, Color.Black.copy(alpha = .5f)))))
        Row(Modifier.align(Alignment.TopStart).padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val labels = (review.objectLabels + review.audioLabels + review.subLabels).distinct()
            if (labels.isNotEmpty()) Row(
                Modifier.background(if (review.hasBeenReviewed) Color(0xFF166534) else Color(0xFF6B7280), RoundedCornerShape(4.dp)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.take(4).forEach { label ->
                    Icon(if (label in review.audioLabels) Icons.Outlined.VolumeUp else objectIcon(label),
                        "Detected $label", Modifier.size(16.dp), tint = Color.White)
                }
                if (labels.size > 4) Text("+${labels.size - 4}", color = Color.White, fontSize = 11.sp)
            }
            review.threatLevel?.takeIf { !review.hasBeenReviewed && it > 0 }?.let {
                Text("! $it", color = Color.White, fontSize = 11.sp, modifier = Modifier.background(Color(0xFF6B7280)).padding(5.dp))
            }
        }
        Text(cameraName, color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xDB25262B), RoundedCornerShape(4.dp)).padding(5.dp))
        if (pending || review.endTime == null) CircularProgressIndicator(Modifier.align(Alignment.BottomStart).padding(10.dp).size(16.dp)
            .semantics { contentDescription = if (pending) "Updating event" else "Event in progress" }, color = Color.White, strokeWidth = 2.dp)
        else Text(reviewTimeAgo(review.startTime, System.currentTimeMillis() / 1000.0), color = Color.White, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth(.6f))
        Text(stamp, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}
