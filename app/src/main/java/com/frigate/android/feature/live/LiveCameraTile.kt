package com.frigate.android.feature.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.HelpOutline
import com.frigate.android.ui.icons.DirectionsCar
import com.frigate.android.ui.icons.LocalShipping
import androidx.compose.material.icons.outlined.Person
import com.frigate.android.ui.icons.Pets
import com.frigate.android.ui.icons.TwoWheeler
import com.frigate.android.ui.icons.VideocamOff
import com.frigate.android.ui.icons.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class LiveTileVisualState(
    val name: String,
    val tileAspect: Float = 16f / 9,
    val pictureAspect: Float = 16f / 9,
    val expanded: Boolean = false,
    val enabled: Boolean = true,
    val offline: Boolean = false,
    val motion: Boolean = false,
    val tracking: Boolean = false,
    val objects: Set<String> = emptySet(),
    val videoReady: Boolean = false,
    val mediaFailed: Boolean = false,
    val snapshotFailed: Boolean = false,
    /** The panel of a camera has no name: in the PWA the name stays in the tiles of the grid. */
    val showName: Boolean = true,
    /** The panel of a camera has no rounded corners: the picture of the PWA goes to the edges. */
    val rounded: Boolean = true,
)

@Composable
internal fun LiveCameraTile(
    state: LiveTileVisualState,
    snapshot: ImageBitmap?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    video: @Composable BoxScope.() -> Unit = {},
) {
    val shape = when {
        !state.rounded -> RectangleShape
        state.expanded -> RoundedCornerShape(16.dp)
        else -> RoundedCornerShape(8.dp)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(state.tileAspect)
            .clip(shape).background(Color.Black)
            .then(if (state.enabled && !state.offline && state.tracking) {
                Modifier.border(3.dp, Color(0xFFEA580C), shape)
            } else Modifier)
            .semantics {
                stateDescription = when {
                    !state.enabled -> "Camera disabled"
                    state.offline -> "Camera offline"
                    state.tracking -> "Object tracking active"
                    state.videoReady -> "Live video"
                    else -> "Camera image"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val pictureModifier = if (maxWidth / maxHeight > state.pictureAspect) {
            Modifier.fillMaxHeight().aspectRatio(state.pictureAspect)
        } else {
            Modifier.fillMaxWidth().aspectRatio(state.pictureAspect)
        }
        if (state.enabled) {
            Box(pictureModifier, contentAlignment = Alignment.Center) { video() }
            if (!state.videoReady) {
                snapshot?.let {
                    Image(it, "${state.name} snapshot", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                if (snapshot == null && !state.offline && !state.mediaFailed) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
            Box(pictureModifier.background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Transparent),
            )))
        }

        if (!state.enabled || state.offline) {
            Box(Modifier.fillMaxSize().background(if (state.offline) Color(0x80000000) else Color(0xFF18181B)))
            Column(
                Modifier.padding(16.dp).background(Color(0xAA18181B), RoundedCornerShape(8.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (state.enabled) Icons.Outlined.WarningAmber else Icons.Outlined.VideocamOff,
                    null, tint = Color.White, modifier = Modifier.size(24.dp),
                )
                Text(if (state.enabled) "Camera offline" else "Camera disabled", color = Color.White,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.enabled && state.objects.isNotEmpty()) {
            val limit = ((maxWidth.value * 0.4f - 20) / 20).toInt().coerceIn(1, 4)
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF6B7280), Color(0xFF4B5563))), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.objects.sorted().take(limit).forEach { label ->
                    Icon(objectIcon(label), "Detected $label", Modifier.size(16.dp), tint = Color.White)
                }
                if (state.objects.size > limit) {
                    Text("+${state.objects.size - limit}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.semantics { contentDescription = state.objects.sorted().drop(limit).joinToString(", ") })
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = maxWidth * 0.52f),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.showName) {
                Text(
                    state.name,
                    Modifier.weight(1f, fill = false).background(Color(0xDC4B5563), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.enabled && !state.offline && state.motion) {
                // The dot of the motion pulses in the PWA: the eye finds it in a dark picture.
                val pulse by rememberInfiniteTransition(label = "motion").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "motionAlpha",
                )
                Box(Modifier.size(8.dp).background(Color(0xFFEF4444).copy(alpha = pulse), CircleShape)
                    .semantics { contentDescription = "Motion detected" })
            }
        }
        if (state.enabled && !state.offline && state.mediaFailed) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD18181B)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Live video unavailable", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry", color = Color.White) }
            }
        } else if (state.enabled && !state.offline && state.snapshotFailed && !state.videoReady) {
            Text(
                "Image refresh unavailable", Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0xBB18181B), RoundedCornerShape(4.dp)).padding(4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal fun objectIcon(label: String) = when (label.lowercase()) {
    "person" -> Icons.Outlined.Person
    "car" -> Icons.Outlined.DirectionsCar
    "truck" -> Icons.Outlined.LocalShipping
    "motorcycle", "bicycle" -> Icons.Outlined.TwoWheeler
    "cat", "dog" -> Icons.Outlined.Pets
    else -> Icons.AutoMirrored.Outlined.HelpOutline
}
