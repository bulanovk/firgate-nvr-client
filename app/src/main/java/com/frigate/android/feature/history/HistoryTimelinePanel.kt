package com.frigate.android.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import com.frigate.android.ui.icons.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private val TIMELINE_ROW_HEIGHT = 8.dp
private val CURSOR_HANDLE_HEIGHT = 26.dp

/**
 * The vertical archive timeline.
 *
 * The newest moment is at the top, every row covers one segment of the zoom level and the red
 * cursor marks the playhead. The scale follows the playhead until the user takes the timeline into
 * their hands: a scroll of the user is never taken away by the playhead that moves on.
 */
@Composable
internal fun HistoryTimelinePanel(
    segments: List<HistorySegment>,
    zoom: TimelineZoom,
    position: () -> Double,
    onSeek: (Double) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { TIMELINE_ROW_HEIGHT.toPx() }
    val handleHeightPx = with(density) { CURSOR_HANDLE_HEIGHT.toPx() }
    val step = zoom.segmentDuration
    val newestEnd = segments.firstOrNull()?.endTime ?: 0.0

    // The playhead is read where it is drawn and in the follow effect: a step of it must not
    // recompose the whole panel with its list.
    val rowsFromTop: () -> Double = { ((newestEnd - position()) / step).coerceAtLeast(0.0) }
    var following by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(HistoryColors.panel),
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(following, segments, viewportHeightPx, rowHeightPx, newestEnd, step) {
            if (!following || segments.isEmpty()) return@LaunchedEffect
            snapshotFlow { rowsFromTop() }.collect { rows ->
                val index = rows.roundToInt().coerceIn(0, segments.lastIndex)
                val visible = listState.layoutInfo.visibleItemsInfo
                    .takeIf { it.isNotEmpty() }
                    ?.let { items -> items.first().index..items.last().index }
                val half = (viewportHeightPx / rowHeightPx / 2).roundToInt()
                val target = historyTimelineScrollTarget(index, visible, half) ?: return@collect
                listState.scrollToItem(target, 0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // The rows rarely change while the video plays: they are drawn into a layer once
                // and every frame of the video only copies it.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(segments, step, newestEnd) {
                    detectTapGestures { offset ->
                        following = true
                        val contentOffset = listState.firstVisibleItemIndex * rowHeightPx +
                            listState.firstVisibleItemScrollOffset + offset.y
                        onSeek(newestEnd - contentOffset / rowHeightPx * step)
                    }
                }
                .pointerInput(Unit) {
                    observeVerticalScroll { following = false }
                },
        ) {
            items(segments, key = { it.startTime }) { segment ->
                TimelineRow(segment)
            }
        }

        TimelinePlayhead(
            position = position,
            newestEnd = newestEnd,
            step = step,
            listState = listState,
            rowHeightPx = rowHeightPx,
            handleHeightPx = handleHeightPx,
            onDragStart = { following = false },
            onSeek = onSeek,
        )

        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
            Column {
                IconButton(onClick = onZoomIn, enabled = canZoomIn, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, "Zoom the timeline in", tint = Color.White)
                }
                IconButton(onClick = onZoomOut, enabled = canZoomOut, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Remove, "Zoom the timeline out", tint = Color.White)
                }
            }
        }
    }
}

/**
 * The moment under the cursor while it is dragged.
 *
 * [dragPx] is the total of the gesture since its start, so it is applied to the moment the gesture
 * started at ([base]), never to the current position: that one moves with every seek and the same
 * drag would be counted again with every event.
 */
internal fun historyScrubPosition(base: Double, dragPx: Float, rowHeightPx: Float, step: Double): Double =
    base - dragPx / rowHeightPx * step

/** The red cursor: the only part of the panel that reads the playhead while the video plays. */
@Composable
private fun TimelinePlayhead(
    position: () -> Double,
    newestEnd: Double,
    step: Double,
    listState: LazyListState,
    rowHeightPx: Float,
    handleHeightPx: Float,
    onDragStart: () -> Unit,
    onSeek: (Double) -> Unit,
) {
    var dragBase by remember { mutableDoubleStateOf(0.0) }
    // Nothing here reads the playhead while composing: the offset is read when the cursor is laid
    // out and only the clock inside the handle is composed again, once per second. The part of the
    // timeline outside the panel is clipped by the panel, so a cursor out of view needs no branch.
    TimelineCursor(
        position = position,
        modifier = Modifier.offset {
            val rowsFromTop = ((newestEnd - position()) / step).coerceAtLeast(0.0)
            val scrolled = listState.firstVisibleItemIndex * rowHeightPx + listState.firstVisibleItemScrollOffset
            IntOffset(0, (rowsFromTop * rowHeightPx - scrolled - handleHeightPx).roundToInt())
        },
        onDragStart = {
            dragBase = position()
            onDragStart()
        },
        onDrag = { deltaPx ->
            onSeek(historyScrubPosition(dragBase, deltaPx, rowHeightPx, step))
        },
    )
}

/**
 * Notices a scroll of the user and stops the timeline from following the playhead.
 *
 * The movement is read in the initial pass of the pointer events: the list consumes the drag
 * itself, the observer only looks at the raw movement and never takes the event away.
 */
private suspend fun PointerInputScope.observeVerticalScroll(onScroll: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var moved = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.positionChangedIgnoreConsumed() } ?: continue
            moved += change.positionChangeIgnoreConsumed().y
            if (abs(moved) > viewConfiguration.touchSlop) onScroll()
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun TimelineRow(segment: HistorySegment) {
    // Flat colour per severity: a gradient is a shader on every visible row, redrawn with every
    // video frame. The PWA gradient is deliberately dropped (owner ruling).
    val background: Color = when {
        !segment.hasRecording -> Color.Black
        segment.severity == HistoryTimelineModel.SEVERITY_ALERT -> HistoryColors.alert.copy(alpha = 0.2f)
        segment.severity == HistoryTimelineModel.SEVERITY_DETECTION -> HistoryColors.detection.copy(alpha = 0.2f)
        else -> HistoryColors.panel
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(TIMELINE_ROW_HEIGHT)
            .background(background),
    ) {
        if (segment.hasRecording) {
            MotionLine(segment.firstHalfMotion, Modifier.align(Alignment.TopCenter).offset(y = 1.dp))
            MotionLine(segment.secondHalfMotion, Modifier.align(Alignment.TopCenter).offset(y = 5.dp))
        }
    }
}

@Composable
private fun MotionLine(motion: Double, modifier: Modifier = Modifier) {
    val active = motion > 0.0
    Box(
        modifier
            .height(2.dp)
            .fillMaxWidth(if (active) (motion / 100.0).coerceIn(0.05, 0.5).toFloat() else 0.01f)
            .background(if (active) HistoryColors.motion else HistoryColors.motionIdle, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun TimelineCursor(
    position: () -> Double,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragDelta by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(CURSOR_HANDLE_HEIGHT + 2.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        onDragStart()
                        dragDelta = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragDelta += dragAmount
                        onDrag(dragDelta)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(80.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(HistoryColors.handle),
            contentAlignment = Alignment.Center,
        ) {
            CursorClock(position)
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(HistoryColors.cursor),
        )
    }
}

/** The clock in the handle: the one thing that is composed again when the playhead moves on. */
@Composable
private fun CursorClock(position: () -> Double) {
    val time by remember(position) { derivedStateOf { formatClock(position()) } }
    Text(
        time,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
    )
}

/** The clock of a moment of the day, the way the timeline and the panels show it. */
internal fun formatClock(timestamp: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = Instant.ofEpochSecond(timestamp.toLong()).atZone(zone)
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}

/** The clock of a moment of the day without the seconds. */
internal fun formatClockShort(timestamp: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = Instant.ofEpochSecond(timestamp.toLong()).atZone(zone)
    return "%02d:%02d".format(local.hour, local.minute)
}

internal val TimelineRowHeight: Dp = TIMELINE_ROW_HEIGHT
