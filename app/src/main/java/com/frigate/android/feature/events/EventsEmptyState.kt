package com.frigate.android.feature.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The reference names the severity of the empty tab and tells an empty range apart from a
 * list that the filters emptied out.
 */
internal fun eventsEmptyMessage(type: EventsType, filtered: Boolean): String {
    val noun = if (type == EventsType.DETECTION) "detections" else "alerts"
    return if (filtered) "There are no $noun to review" else "No $noun to review"
}

/** The empty card of the reference is a large mark above the message. */
private val eventsEmptyIconSize = 56.dp

/** The mark of the reference empty card: the folder outline of the mockup with the mark of the PWA. */
private val eventsEmptyIcon: ImageVector = ImageVector.Builder(
    name = "Events empty", defaultWidth = eventsEmptyIconSize, defaultHeight = eventsEmptyIconSize,
    viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = addPathNodes("M3 8V5h6l2 3h10v12H3Z"),
    stroke = SolidColor(Color.White),
    strokeLineWidth = 1.8f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).addPath(
    pathData = addPathNodes("m9 10 2 2 4-4"),
    stroke = SolidColor(Color.White),
    strokeLineWidth = 1.8f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

@Composable
internal fun EventsEmptyState(type: EventsType, filtered: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(eventsEmptyIcon, null, Modifier.testTag("events-empty-icon").size(eventsEmptyIconSize),
            tint = Color(0xFFA3A3A3))
        Text(eventsEmptyMessage(type, filtered), fontSize = 16.sp, color = Color(0xFFA3A3A3),
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
