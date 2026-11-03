package com.frigate.android.feature.live

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The outline paths of the approved docs/design/live-camera-panel.html, not Material substitutes. */
internal object LiveCameraHeaderIcons {
    val back = outline("Back", "m14 5-7 7 7 7M7 12h14")
    val history = outline("History", "M3 12a9 9 0 1 0 3-6.7L3 8M3 4v4h4M12 7v5l3 2")
    val fullscreen = outline("Fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val compress = outline("Compress", "M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5")
    val pip = mixed(
        "Picture in picture",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z",
        "M13 12h5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1Z",
    )
    val record = mixed(
        "Manual recording",
        "M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z",
        "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
    )
    val audio = outline("Audio", "M4 9v6h4l5 4V5L8 9ZM16 9a4 4 0 0 1 0 6")
    val muted = outline("Muted", "M4 9v6h4l5 4V5L8 9Zm12 0 5 6m0-6-5 6")
    val snapshot = outline(
        "Snapshot",
        "M4 8h3l1.5-2h7L17 8h3v11H4Z" +
            "M15.5 13.5a3.5 3.5 0 1 0-7 0a3.5 3.5 0 1 0 7 0",
    )
    val settings = outline(
        "Settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08" +
            "a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51" +
            "a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08" +
            "a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18" +
            "a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39" +
            "a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09" +
            "a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25" +
            "a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2zM15 12a3 3 0 1 0-6 0a3 3 0 1 0 6 0",
    )

    private fun outline(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 21.dp, defaultHeight = 21.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(path),
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    /** A shape that carries an outline and a filled part, as the picture in picture icon does. */
    private fun mixed(name: String, outlinePath: String, filledPath: String): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 21.dp, defaultHeight = 21.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(outlinePath),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes(filledPath),
            fill = SolidColor(Color.White),
        ).build()
}
