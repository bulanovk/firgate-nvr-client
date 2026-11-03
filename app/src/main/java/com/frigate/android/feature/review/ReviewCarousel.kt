package com.frigate.android.feature.review

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ReviewMediaEndpoint
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Grid key of the carousel item, deliberately different from any camera id. */
internal const val REVIEW_CAROUSEL_ITEM_KEY = "review-carousel"

private const val CARD_WIDTH_DP = 164
private const val CARD_PREVIEW_HEIGHT_DP = 100
private const val AGE_REFRESH_MILLIS = 30_000L

private object CarouselColors {
    val preview = Color(0xFF26292B)
    val muted = Color(0xFFA7A9AE)
    val name = Color(0xFFD9DADD)
    val mark = Color(0xFFBBBFC5)
    val line = Color(0xFF34363B)
    val skeletonTop = Color(0xFF24262B)
    val skeletonBottom = Color(0xFF151619)
    val ongoingBackground = Color(0xEB21180F)
    val ongoingBorder = Color(0xFF976333)
    val ongoingText = Color(0xFFFFCA9D)
    val failure = Color(0xFFEF4444)
}

/**
 * The strip of unreviewed alerts that sits above the camera grid on the Live dashboard.
 *
 * The block disappears completely when there is nothing to review, matching the PWA behaviour of
 * not reserving any space for an empty list.
 */
@Composable
internal fun ReviewCarousel(
    state: ReviewUiState,
    profile: ServerProfile,
    cookie: String,
    cameraNames: Map<String, String>,
    onOpen: (FrigateReview) -> Unit,
    onMarkViewed: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val mode = reviewCarouselMode(state)
    if (mode == ReviewCarouselMode.HIDDEN) return

    val cache = remember(profile, cookie) { ReviewThumbnailCache() }
    LaunchedEffect(state.items) { cache.retain(state.items.map { it.id }) }

    val currentNow by rememberUpdatedState(nowSeconds)
    var now by remember { mutableStateOf(currentNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AGE_REFRESH_MILLIS)
            now = currentNow()
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Unreviewed", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("Last hour", color = CarouselColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        when (mode) {
            ReviewCarouselMode.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { CarouselSkeleton() }
            }

            ReviewCarouselMode.ERROR -> Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, CarouselColors.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Couldn't load events",
                    Modifier.weight(1f),
                    color = Color(0xFFD7D8DB),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Retry", color = Color.White)
                }
            }

            ReviewCarouselMode.ITEMS -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(state.items, key = { it.id }) { review ->
                    ReviewCarouselCard(
                        review = review,
                        profile = profile,
                        cookie = cookie,
                        cameraName = cameraNames[review.camera] ?: review.camera,
                        age = reviewTimeAgo(review.startTime, now),
                        marking = review.id in state.markingIds,
                        markFailed = review.id in state.markFailedIds,
                        cache = cache,
                        onOpen = { onOpen(review) },
                        onMarkViewed = { onMarkViewed(review.id) },
                    )
                }
            }

            ReviewCarouselMode.HIDDEN -> Unit
        }
    }
}

@Composable
private fun ReviewCarouselCard(
    review: FrigateReview,
    profile: ServerProfile,
    cookie: String,
    cameraName: String,
    age: String,
    marking: Boolean,
    markFailed: Boolean,
    cache: ReviewThumbnailCache,
    onOpen: () -> Unit,
    onMarkViewed: () -> Unit,
) {
    val entry = cache.entry(review.id)
    LaunchedEffect(profile, cookie, review.id, review.thumbnailPath) {
        if (cache.entry(review.id)?.loaded == true) return@LaunchedEffect
        val available = ReviewMediaEndpoint.thumbnailUrl(profile, review.thumbnailPath) != null
        if (!available) {
            cache.store(review.id, ThumbnailEntry(loaded = true))
            return@LaunchedEffect
        }
        val bytes = runCatching { ReviewThumbnailLoader.load(profile, cookie, review) }.getOrNull()
        val bitmap = bytes?.let {
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        cache.store(review.id, ThumbnailEntry(bitmap = bitmap, loaded = true))
    }

    Column(Modifier.width(CARD_WIDTH_DP.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .height(CARD_PREVIEW_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CarouselColors.preview)
                .clickable(onClickLabel = "Open $cameraName event", onClick = onOpen),
        ) {
            val bitmap = entry?.bitmap
            when {
                bitmap != null -> Image(
                    bitmap.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                entry?.loaded == true -> Text(
                    "Preview unavailable",
                    Modifier.align(Alignment.Center),
                    color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.labelSmall,
                )

                else -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))),
                ),
            )
            Text(
                age,
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            if (review.isOngoing) {
                val shape = RoundedCornerShape(4.dp)
                Text(
                    "Still ongoing",
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(CarouselColors.ongoingBackground, shape)
                        .border(1.dp, CarouselColors.ongoingBorder, shape)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    color = CarouselColors.ongoingText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    cameraName,
                    color = CarouselColors.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = reviewObjectSummary(review.objectLabels)
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        color = CarouselColors.muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onMarkViewed,
                modifier = Modifier.size(48.dp),
            ) {
                if (marking) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = CarouselColors.mark,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Check,
                        "Mark $cameraName event as reviewed",
                        tint = if (markFailed) CarouselColors.failure else CarouselColors.mark,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarouselSkeleton() {
    Column(Modifier.width(CARD_WIDTH_DP.dp)) {
        Box(
            Modifier.fillMaxWidth().height(CARD_PREVIEW_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(8.dp)).background(CarouselColors.skeletonTop),
        )
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(8.dp)).background(CarouselColors.skeletonBottom),
        )
    }
}

/** Decoded review thumbnails of the current carousel, kept while the segment is on screen. */
internal class ReviewThumbnailCache {
    private val entries = mutableStateMapOf<String, ThumbnailEntry>()

    fun entry(id: String): ThumbnailEntry? = entries[id]

    fun store(id: String, entry: ThumbnailEntry) {
        entries[id] = entry
    }

    fun retain(ids: List<String>) {
        val keep = ids.toSet()
        entries.keys.filterNot { it in keep }.forEach { entries.remove(it) }
    }
}

internal data class ThumbnailEntry(val bitmap: Bitmap? = null, val loaded: Boolean = false)
