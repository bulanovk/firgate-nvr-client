package com.frigate.android.feature.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.review.ReviewThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The events of the selected camera.
 *
 * Every event of the range is listed with its preview, the severity, the objects and the time of
 * the day. A tap moves the playhead to the start of the event and starts the playback.
 */
@Composable
internal fun HistoryEventsPanel(
    reviews: List<FrigateReview>,
    profile: ServerProfile,
    cookie: String,
    positionSeconds: Double,
    onSeekAndPlay: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnails = remember { mutableStateMapOf<String, Bitmap?>() }

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
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(reviews, key = { it.id }) { review ->
            LaunchedEffect(review.id, profile, cookie) {
                if (thumbnails.containsKey(review.id)) return@LaunchedEffect
                val bytes = runCatching { ReviewThumbnailLoader.load(profile, cookie, review) }.getOrNull()
                val bitmap = bytes?.let {
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                thumbnails[review.id] = bitmap
            }
            HistoryEventCard(
                review = review,
                thumbnail = thumbnails[review.id],
                current = positionSeconds >= review.startTime - HistoryRange.REVIEW_PADDING &&
                    positionSeconds <= (review.endTime ?: positionSeconds) + HistoryRange.REVIEW_PADDING,
                onClick = { onSeekAndPlay(review.startTime - HistoryRange.REVIEW_PADDING) },
            )
        }
    }
}

@Composable
private fun HistoryEventCard(
    review: FrigateReview,
    thumbnail: Bitmap?,
    current: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Play this event", onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(HistoryColors.panelSurface)
                .then(
                    if (current) {
                        Modifier.border(2.dp, HistoryColors.cursor, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (thumbnail != null) {
                Image(
                    thumbnail.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "Preview unavailable",
                    Modifier.align(Alignment.Center),
                    color = HistoryColors.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp),
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
                if (review.objectLabels.any { it.contains("car") }) Icons.Outlined.DirectionsCar else Icons.Outlined.Person,
                null,
                tint = HistoryColors.muted,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatClock(review.startTime),
                color = HistoryColors.foreground,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                review.objectLabels.joinToString(", ").ifBlank { "Object" },
                color = HistoryColors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal fun severityColor(severity: String): Color = when (severity) {
    HistoryTimelineModel.SEVERITY_ALERT -> HistoryColors.alert
    HistoryTimelineModel.SEVERITY_DETECTION -> HistoryColors.detection
    else -> HistoryColors.muted
}
