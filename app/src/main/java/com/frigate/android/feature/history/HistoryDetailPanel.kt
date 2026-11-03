package com.frigate.android.feature.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.DirectionsCar
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview

/**
 * The detail of the events of the selected camera.
 *
 * Every event opens into the moments of its lifetime; a tap on a moment moves the playhead there.
 */
@Composable
internal fun HistoryDetailPanel(
    reviews: List<FrigateReview>,
    positionSeconds: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandedIds = remember { mutableStateOf(setOf<String>()) }

    if (reviews.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No events for this period",
                color = HistoryColors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(reviews, key = { it.id }) { review ->
            val expanded = review.id in expandedIds.value
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HistoryColors.panelSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable {
                            expandedIds.value = if (expanded) {
                                expandedIds.value - review.id
                            } else {
                                expandedIds.value + review.id
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(severityColor(review.severity)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (review.objectLabels.any { it.contains("car") }) {
                            Icons.Outlined.DirectionsCar
                        } else {
                            Icons.Outlined.Person
                        },
                        null,
                        tint = HistoryColors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        review.objectLabels.joinToString(", ").ifBlank { "Object" },
                        color = HistoryColors.foreground,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${review.objectLabels.size} · ${historyDuration(review)}",
                        color = HistoryColors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    formatClock(review.startTime),
                    color = HistoryColors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                AnimatedVisibility(visible = expanded) {
                    Column {
                        historyMoments(review).forEach { moment ->
                            val active = kotlin.math.abs(moment.timestamp - positionSeconds) < 8.0
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clickable { onSeek(moment.timestamp) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    formatClock(moment.timestamp),
                                    color = if (active) HistoryColors.foreground else HistoryColors.muted,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    moment.label,
                                    color = if (active) HistoryColors.foreground else HistoryColors.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            HorizontalDivider(color = HistoryColors.line)
            Spacer(Modifier.height(12.dp))
            Text(
                "Detail settings",
                color = HistoryColors.foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Annotation offset and the always expanded state are applied on the device.",
                color = HistoryColors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class HistoryMoment(val timestamp: Double, val label: String)

/**
 * The moments of the lifetime of an event.
 *
 * The API does not expose the lifecycle of a single object, so the moments are derived from the
 * interval of the event itself.
 */
private fun historyMoments(review: FrigateReview): List<HistoryMoment> {
    val end = review.endTime ?: return listOf(
        HistoryMoment(review.startTime, "Detected"),
        HistoryMoment(review.startTime, "Still ongoing"),
    )
    val middle = review.startTime + (end - review.startTime) / 2.0
    return listOf(
        HistoryMoment(review.startTime, "Detected"),
        HistoryMoment(middle, "Active"),
        HistoryMoment(end, "Left the zone"),
    )
}

private fun historyDuration(review: FrigateReview): String {
    val end = review.endTime ?: return "ongoing"
    return "${(end - review.startTime).toInt()} s"
}
