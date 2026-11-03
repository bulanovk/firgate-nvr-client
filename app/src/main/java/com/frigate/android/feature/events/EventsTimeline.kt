package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun EventsTimeline(model: EventsTimelineModel, timezone: String, onReview: (String) -> Unit, onSeek: (Double) -> Unit) {
    var scale by remember(model.range) { mutableFloatStateOf(1f) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val seek by rememberUpdatedState(onSeek)
    val formatter = remember(timezone) { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of(timezone)) }
    Row(Modifier.fillMaxHeight().width(120.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).pointerInput(model.range) {
            detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f) }
        }) {
            val height = 600.dp * scale
            Box(Modifier.fillMaxWidth().height(height)) {
                timelineTicks(model.range, timezone, maxTicks = (height.value / 32).toInt().coerceAtLeast(2)).forEach { tick ->
                    Text(tick.label, fontSize = 10.sp, modifier = Modifier.offset(y = height * (1 - model.fractionAt(tick.timestamp)).toFloat())
                        .clickable { onSeek(tick.timestamp) })
                }
                model.items.forEach { review ->
                    val top = (1 - model.fractionAt(review.endTime ?: model.range.before)).toFloat()
                    val bottom = (1 - model.fractionAt(review.startTime)).toFloat()
                    Box(Modifier.offset(x = 50.dp, y = height * top).width(28.dp).height(maxOf(4.dp, height * (bottom - top)))
                        .background(severityColor(review.severity).copy(alpha = if (review.hasBeenReviewed) .45f else 1f))
                        .semantics { contentDescription = "Timeline event: ${review.camera}, ${formatter.format(Instant.ofEpochSecond(review.startTime.toLong()))}" }
                        .clickable { onReview(review.id) })
                }
            }
        }
        BoxWithConstraints(Modifier.width(8.dp).fillMaxHeight().background(Color(0xff26282d))
            .semantics { contentDescription = "Timeline overview" }
            .pointerInput(model.range) {
                detectTapGestures { point ->
                    val fraction = (point.y / size.height).coerceIn(0f, 1f)
                    seek(model.timeAt(1.0 - fraction))
                    scope.launch { scroll.animateScrollTo((fraction * scroll.maxValue).toInt()) }
                }
            }) {
            model.items.forEach { review ->
                Box(Modifier.offset(y = maxHeight * (1 - model.fractionAt(review.startTime)).toFloat())
                    .fillMaxWidth().height(3.dp).background(severityColor(review.severity)))
            }
        }
    }
}

private fun severityColor(severity: String): Color = when (severity) {
    "alert" -> Color(0xffef4444)
    "detection" -> Color(0xffea580c)
    else -> Color(0xfff7b908)
}
