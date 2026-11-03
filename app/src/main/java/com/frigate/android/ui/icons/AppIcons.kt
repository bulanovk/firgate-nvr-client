package com.frigate.android.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * The Material outlined icons that the app uses and that are not part of material-icons-core.
 *
 * The paths are the path data of material-icons-extended, copied one to one (a round-trip check
 * against the library confirmed that the nodes are identical when they were taken). They live here
 * so that the app does not ship the whole extended set: it is about 80 MB of classes, almost all of
 * which is never used.
 *
 * Use them like the library icons: `Icons.Outlined.Videocam`, after importing
 * `com.frigate.android.ui.icons.Videocam`.
 */

private fun icon(name: String, autoMirror: Boolean, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        paths.forEach { path -> addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }
    }.build()

val Icons.Outlined.CalendarMonth: ImageVector
    get() = CalendarMonthIcon

private val CalendarMonthIcon = icon(
    "Outlined.CalendarMonth",
    autoMirror = false,
    "M19 4h-1V2h-2v2H8V2H6v2H5C3.89 4 3.01 4.9 3.01 6L3 20c0 1.1 0.89 2 2 2h14" +
        "c1.1 0 2 -0.9 2 -2V6C21 4.9 20.1 4 19 4ZM19 20H5V10h14V20ZM19 8H5V6h14V8ZM9 14H7v-2h2V14" +
        "ZM13 14h-2v-2h2V14ZM17 14h-2v-2h2V14ZM9 18H7v-2h2V18ZM13 18h-2v-2h2V18ZM17 18h-2v-2h2V18" +
        "Z",
)

val Icons.Outlined.CameraAlt: ImageVector
    get() = CameraAltIcon

private val CameraAltIcon = icon(
    "Outlined.CameraAlt",
    autoMirror = false,
    "M20 4h-3.17L15 2L9 2L7.17 4L4 4c-1.1 0 -2 0.9 -2 2v12c0 1.1 0.9 2 2 2h16" +
        "c1.1 0 2 -0.9 2 -2L22 6c0 -1.1 -0.9 -2 -2 -2ZM20 18L4 18L4 6h4.05l1.83 -2h4.24l1.83 2" +
        "L20 6v12ZM12 7c-2.76 0 -5 2.24 -5 5s2.24 5 5 5s5 -2.24 5 -5s-2.24 -5 -5 -5ZM12 15" +
        "c-1.65 0 -3 -1.35 -3 -3s1.35 -3 3 -3s3 1.35 3 3s-1.35 3 -3 3Z",
)

val Icons.Outlined.DirectionsCar: ImageVector
    get() = DirectionsCarIcon

private val DirectionsCarIcon = icon(
    "Outlined.DirectionsCar",
    autoMirror = false,
    "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-0.66 0 -1.21 0.42 -1.42 1.01L3 12v8" +
        "c0 0.55 0.45 1 1 1h1c0.55 0 1 -0.45 1 -1v-1h12v1c0 0.55 0.45 1 1 1h1c0.55 0 1 -0.45 1 -1" +
        "v-8l-2.08 -5.99ZM6.85 7h10.29l1.08 3.11H5.77L6.85 7ZM19 17H5v-5h14v5Z",
    "M7.5 14.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0",
    "M16.5 14.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0",
)

val Icons.Outlined.DoneAll: ImageVector
    get() = DoneAllIcon

private val DoneAllIcon = icon(
    "Outlined.DoneAll",
    autoMirror = false,
    "M18 7l-1.41 -1.41l-6.34 6.34l1.41 1.41L18 7ZM22.24 5.59L11.66 16.17L7.48 12l-1.41 1.41" +
        "L11.66 19l12 -12l-1.42 -1.41ZM0.41 13.41L6 19l1.41 -1.41L1.83 12L0.41 13.41Z",
)

val Icons.Outlined.Download: ImageVector
    get() = DownloadIcon

private val DownloadIcon = icon(
    "Outlined.Download",
    autoMirror = false,
    "M19 9h-4L15 3L9 3v6L5 9l7 7l7 -7ZM11 11L11 5h2v6h1.17L12 13.17L9.83 11L11 11ZM5 18h14v2" +
        "L5 20Z",
)

val Icons.Outlined.Event: ImageVector
    get() = EventIcon

private val EventIcon = icon(
    "Outlined.Event",
    autoMirror = false,
    "M19 4h-1L18 2h-2v2L8 4L8 2L6 2v2L5 4c-1.11 0 -1.99 0.9 -1.99 2L3 20c0 1.1 0.89 2 2 2h14" +
        "c1.1 0 2 -0.9 2 -2L21 6c0 -1.1 -0.9 -2 -2 -2ZM19 20L5 20L5 10h14v10ZM19 8L5 8L5 6h14v2Z" +
        "M12 13h5v5h-5Z",
)

val Icons.Outlined.FileDownload: ImageVector
    get() = FileDownloadIcon

private val FileDownloadIcon = icon(
    "Outlined.FileDownload",
    autoMirror = false,
    "M18 15v3H6v-3H4v3c0 1.1 0.9 2 2 2h12c1.1 0 2 -0.9 2 -2v-3H18ZM17 11l-1.41 -1.41L13 12.17" +
        "V4h-2v8.17L8.41 9.59L7 11l5 5L17 11Z",
)

val Icons.Outlined.FilterAlt: ImageVector
    get() = FilterAltIcon

private val FilterAltIcon = icon(
    "Outlined.FilterAlt",
    autoMirror = false,
    "M7 6h10l-5.01 6.3L7 6ZM4.25 5.61C6.27 8.2 10 13 10 13v6c0 0.55 0.45 1 1 1h2" +
        "c0.55 0 1 -0.45 1 -1v-6c0 0 3.72 -4.8 5.74 -7.39C20.25 4.95 19.78 4 18.95 4H5.04" +
        "C4.21 4 3.74 4.95 4.25 5.61Z",
)

val Icons.Outlined.Forward10: ImageVector
    get() = Forward10Icon

private val Forward10Icon = icon(
    "Outlined.Forward10",
    autoMirror = false,
    "M18 13c0 3.31 -2.69 6 -6 6s-6 -2.69 -6 -6s2.69 -6 6 -6v4l5 -5l-5 -5v4" +
        "c-4.42 0 -8 3.58 -8 8c0 4.42 3.58 8 8 8s8 -3.58 8 -8H18Z",
    "M10.9 16l0 -4.27l-0.09 0l-1.77 0.63l0 0.69l1.01 -0.31l0 3.26Z",
    "M14.32 11.78c-0.18 -0.07 -0.37 -0.1 -0.59 -0.1s-0.41 0.03 -0.59 0.1" +
        "s-0.33 0.18 -0.45 0.33s-0.23 0.34 -0.29 0.57s-0.1 0.5 -0.1 0.82v0.74" +
        "c0 0.32 0.04 0.6 0.11 0.82s0.17 0.42 0.3 0.57s0.28 0.26 0.46 0.33s0.37 0.1 0.59 0.1" +
        "s0.41 -0.03 0.59 -0.1s0.33 -0.18 0.45 -0.33s0.22 -0.34 0.29 -0.57s0.1 -0.5 0.1 -0.82" +
        "V13.5c0 -0.32 -0.04 -0.6 -0.11 -0.82s-0.17 -0.42 -0.3 -0.57S14.49 11.85 14.32 11.78Z" +
        "M14.33 14.35c0 0.19 -0.01 0.35 -0.04 0.48s-0.06 0.24 -0.11 0.32s-0.11 0.14 -0.19 0.17" +
        "s-0.16 0.05 -0.25 0.05s-0.18 -0.02 -0.25 -0.05s-0.14 -0.09 -0.19 -0.17" +
        "s-0.09 -0.19 -0.12 -0.32s-0.04 -0.29 -0.04 -0.48v-0.97c0 -0.19 0.01 -0.35 0.04 -0.48" +
        "s0.06 -0.23 0.12 -0.31s0.11 -0.14 0.19 -0.17s0.16 -0.05 0.25 -0.05s0.18 0.02 0.25 0.05" +
        "s0.14 0.09 0.19 0.17s0.09 0.18 0.12 0.31s0.04 0.29 0.04 0.48V14.35Z",
)

val Icons.Outlined.Fullscreen: ImageVector
    get() = FullscreenIcon

private val FullscreenIcon = icon(
    "Outlined.Fullscreen",
    autoMirror = false,
    "M7 14L5 14v5h5v-2L7 17v-3ZM5 10h2L7 7h3L10 5L5 5v5ZM17 17h-3v2h5v-5h-2v3ZM14 5v2h3v3h2" +
        "L19 5h-5Z",
)

val Icons.Outlined.FullscreenExit: ImageVector
    get() = FullscreenExitIcon

private val FullscreenExitIcon = icon(
    "Outlined.FullscreenExit",
    autoMirror = false,
    "M5 16h3v3h2v-5L5 14v2ZM8 8L5 8v2h5L10 5L8 5v3ZM14 19h2v-3h3v-2h-5v5ZM16 8L16 5h-2v5h5" +
        "L19 8h-3Z",
)

val Icons.Outlined.GraphicEq: ImageVector
    get() = GraphicEqIcon

private val GraphicEqIcon = icon(
    "Outlined.GraphicEq",
    autoMirror = false,
    "M7 18h2L9 6L7 6v12ZM11 22h2L13 2h-2v20ZM3 14h2v-4L3 10v4ZM15 18h2L17 6h-2v12ZM19 10v4h2" +
        "v-4h-2Z",
)

val Icons.Outlined.GridOn: ImageVector
    get() = GridOnIcon

private val GridOnIcon = icon(
    "Outlined.GridOn",
    autoMirror = false,
    "M20 2L4 2c-1.1 0 -2 0.9 -2 2v16c0 1.1 0.9 2 2 2h16c1.1 0 2 -0.9 2 -2L22 4" +
        "c0 -1.1 -0.9 -2 -2 -2ZM8 20L4 20v-4h4v4ZM8 14L4 14v-4h4v4ZM8 8L4 8L4 4h4v4ZM14 20h-4v-4" +
        "h4v4ZM14 14h-4v-4h4v4ZM14 8h-4L10 4h4v4ZM20 20h-4v-4h4v4ZM20 14h-4v-4h4v4ZM20 8h-4L16 4" +
        "h4v4Z",
)

val Icons.Outlined.History: ImageVector
    get() = HistoryIcon

private val HistoryIcon = icon(
    "Outlined.History",
    autoMirror = false,
    "M13 3c-4.97 0 -9 4.03 -9 9L1 12l3.89 3.89l0.07 0.14L9 12L6 12c0 -3.87 3.13 -7 7 -7" +
        "s7 3.13 7 7s-3.13 7 -7 7c-1.93 0 -3.68 -0.79 -4.94 -2.06l-1.42 1.42" +
        "C8.27 19.99 10.51 21 13 21c4.97 0 9 -4.03 9 -9s-4.03 -9 -9 -9ZM12 8v5l4.25 2.52" +
        "l0.77 -1.28l-3.52 -2.09L13.5 8Z",
)

val Icons.Outlined.LocalShipping: ImageVector
    get() = LocalShippingIcon

private val LocalShippingIcon = icon(
    "Outlined.LocalShipping",
    autoMirror = false,
    "M20 8h-3L17 4L3 4c-1.1 0 -2 0.9 -2 2v11h2c0 1.66 1.34 3 3 3s3 -1.34 3 -3h6" +
        "c0 1.66 1.34 3 3 3s3 -1.34 3 -3h2v-5l-3 -4ZM19.5 9.5l1.96 2.5L17 12L17 9.5h2.5ZM6 18" +
        "c-0.55 0 -1 -0.45 -1 -1s0.45 -1 1 -1s1 0.45 1 1s-0.45 1 -1 1ZM8.22 15" +
        "c-0.55 -0.61 -1.33 -1 -2.22 -1s-1.67 0.39 -2.22 1L3 15L3 6h12v9L8.22 15ZM18 18" +
        "c-0.55 0 -1 -0.45 -1 -1s0.45 -1 1 -1s1 0.45 1 1s-0.45 1 -1 1Z",
)

val Icons.Outlined.Pause: ImageVector
    get() = PauseIcon

private val PauseIcon = icon(
    "Outlined.Pause",
    autoMirror = false,
    "M6 19h4L10 5L6 5v14ZM14 5v14h4L18 5h-4Z",
)

val Icons.Outlined.Pets: ImageVector
    get() = PetsIcon

private val PetsIcon = icon(
    "Outlined.Pets",
    autoMirror = false,
    "M4.5 9.5m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1 -5 0",
    "M9 5.5m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1 -5 0",
    "M15 5.5m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1 -5 0",
    "M19.5 9.5m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1 -5 0",
    "M17.34 14.86c-0.87 -1.02 -1.6 -1.89 -2.48 -2.91c-0.46 -0.54 -1.05 -1.08 -1.75 -1.32" +
        "c-0.11 -0.04 -0.22 -0.07 -0.33 -0.09c-0.25 -0.04 -0.52 -0.04 -0.78 -0.04" +
        "s-0.53 0 -0.79 0.05c-0.11 0.02 -0.22 0.05 -0.33 0.09c-0.7 0.24 -1.28 0.78 -1.75 1.32" +
        "c-0.87 1.02 -1.6 1.89 -2.48 2.91c-1.31 1.31 -2.92 2.76 -2.62 4.79" +
        "c0.29 1.02 1.02 2.03 2.33 2.32c0.73 0.15 3.06 -0.44 5.54 -0.44h0.18" +
        "c2.48 0 4.81 0.58 5.54 0.44c1.31 -0.29 2.04 -1.31 2.33 -2.32" +
        "c0.31 -2.04 -1.3 -3.49 -2.61 -4.8Z",
)

val Icons.Outlined.PhotoCamera: ImageVector
    get() = PhotoCameraIcon

private val PhotoCameraIcon = icon(
    "Outlined.PhotoCamera",
    autoMirror = false,
    "M14.12 4l1.83 2L20 6v12L4 18L4 6h4.05l1.83 -2h4.24M15 2L9 2L7.17 4L4 4" +
        "c-1.1 0 -2 0.9 -2 2v12c0 1.1 0.9 2 2 2h16c1.1 0 2 -0.9 2 -2L22 6c0 -1.1 -0.9 -2 -2 -2" +
        "h-3.17L15 2ZM12 9c1.65 0 3 1.35 3 3s-1.35 3 -3 3s-3 -1.35 -3 -3s1.35 -3 3 -3m0 -2" +
        "c-2.76 0 -5 2.24 -5 5s2.24 5 5 5s5 -2.24 5 -5s-2.24 -5 -5 -5Z",
)

val Icons.Outlined.Remove: ImageVector
    get() = RemoveIcon

private val RemoveIcon = icon(
    "Outlined.Remove",
    autoMirror = false,
    "M19 13H5v-2h14v2Z",
)

val Icons.Outlined.Replay10: ImageVector
    get() = Replay10Icon

private val Replay10Icon = icon(
    "Outlined.Replay10",
    autoMirror = false,
    "M11.99 5L11.99 1l-5 5l5 5L11.99 7c3.31 0 6 2.69 6 6s-2.69 6 -6 6s-6 -2.69 -6 -6h-2" +
        "c0 4.42 3.58 8 8 8s8 -3.58 8 -8s-3.58 -8 -8 -8ZM10.89 16h-0.85v-3.26l-1.01 0.31v-0.69" +
        "l1.77 -0.63h0.09L10.89 16ZM15.17 14.24c0 0.32 -0.03 0.6 -0.1 0.82s-0.17 0.42 -0.29 0.57" +
        "s-0.28 0.26 -0.45 0.33s-0.37 0.1 -0.59 0.1s-0.41 -0.03 -0.59 -0.1" +
        "s-0.33 -0.18 -0.46 -0.33s-0.23 -0.34 -0.3 -0.57s-0.11 -0.5 -0.11 -0.82v-0.74" +
        "c0 -0.32 0.03 -0.6 0.1 -0.82s0.17 -0.42 0.29 -0.57s0.28 -0.26 0.45 -0.33" +
        "s0.37 -0.1 0.59 -0.1s0.41 0.03 0.59 0.1s0.33 0.18 0.46 0.33s0.23 0.34 0.3 0.57" +
        "s0.11 0.5 0.11 0.82v0.74ZM14.32 13.38c0 -0.19 -0.01 -0.35 -0.04 -0.48" +
        "s-0.07 -0.23 -0.12 -0.31s-0.11 -0.14 -0.19 -0.17s-0.16 -0.05 -0.25 -0.05" +
        "s-0.18 0.02 -0.25 0.05s-0.14 0.09 -0.19 0.17s-0.09 0.18 -0.12 0.31s-0.04 0.29 -0.04 0.48" +
        "v0.97c0 0.19 0.01 0.35 0.04 0.48s0.07 0.24 0.12 0.32s0.11 0.14 0.19 0.17" +
        "s0.16 0.05 0.25 0.05s0.18 -0.02 0.25 -0.05s0.14 -0.09 0.19 -0.17s0.09 -0.19 0.11 -0.32" +
        "s0.04 -0.29 0.04 -0.48v-0.97Z",
)

val Icons.Outlined.Speed: ImageVector
    get() = SpeedIcon

private val SpeedIcon = icon(
    "Outlined.Speed",
    autoMirror = false,
    "M20.38 8.57l-1.23 1.85a8 8 0 0 1 -0.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85 -1.23" +
        "A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74 -1a10 10 0 0 0 -0.27 -10.44Z",
    "M10.59 15.41a2 2 0 0 0 2.83 0l5.66 -8.49l-8.49 5.66a2 2 0 0 0 0 2.83Z",
)

val Icons.Outlined.Today: ImageVector
    get() = TodayIcon

private val TodayIcon = icon(
    "Outlined.Today",
    autoMirror = false,
    "M19 3h-1L18 1h-2v2L8 3L8 1L6 1v2L5 3c-1.11 0 -2 0.9 -2 2v14c0 1.1 0.89 2 2 2h14" +
        "c1.1 0 2 -0.9 2 -2L21 5c0 -1.1 -0.9 -2 -2 -2ZM19 19L5 19L5 9h14v10ZM19 7L5 7L5 5h14v2Z" +
        "M7 11h5v5L7 16Z",
)

val Icons.Outlined.Tune: ImageVector
    get() = TuneIcon

private val TuneIcon = icon(
    "Outlined.Tune",
    autoMirror = false,
    "M3 17v2h6v-2L3 17ZM3 5v2h10L13 5L3 5ZM13 21v-2h8v-2h-8v-2h-2v6h2ZM7 9v2L3 11v2h4v2h2L9 9" +
        "L7 9ZM21 13v-2L11 11v2h10ZM15 9h2L17 7h4L21 5h-4L17 3h-2v6Z",
)

val Icons.Outlined.TwoWheeler: ImageVector
    get() = TwoWheelerIcon

private val TwoWheelerIcon = icon(
    "Outlined.TwoWheeler",
    autoMirror = false,
    "M4.17 11L4.17 11C4.12 11 4.06 11 4 11H4.17M13.41 5H9v2h3.59l2 2H11l-4 2L5 9H0v2h4" +
        "c-2.21 0 -4 1.79 -4 4c0 2.21 1.79 4 4 4c2.21 0 4 -1.79 4 -4l2 2h3l3.49 -6.1l1.01 1.01" +
        "C16.59 12.64 16 13.75 16 15c0 2.21 1.79 4 4 4c2.21 0 4 -1.79 4 -4c0 -2.21 -1.79 -4 -4 -4" +
        "c-0.18 0 -0.36 0.03 -0.53 0.05L17.41 9H20V6l-3.72 1.86L13.41 5L13.41 5ZM20 17" +
        "c-1.1 0 -2 -0.9 -2 -2c0 -1.1 0.9 -2 2 -2c1.1 0 2 0.9 2 2C22 16.1 21.1 17 20 17L20 17Z" +
        "M4 17c-1.1 0 -2 -0.9 -2 -2c0 -1.1 0.9 -2 2 -2c1.1 0 2 0.9 2 2C6 16.1 5.1 17 4 17L4 17Z",
)

val Icons.Outlined.Videocam: ImageVector
    get() = VideocamIcon

private val VideocamIcon = icon(
    "Outlined.Videocam",
    autoMirror = false,
    "M15 8v8H5V8h10m1 -2H4c-0.55 0 -1 0.45 -1 1v10c0 0.55 0.45 1 1 1h12c0.55 0 1 -0.45 1 -1" +
        "v-3.5l4 4v-11l-4 4V7c0 -0.55 -0.45 -1 -1 -1Z",
)

val Icons.Outlined.VideocamOff: ImageVector
    get() = VideocamOffIcon

private val VideocamOffIcon = icon(
    "Outlined.VideocamOff",
    autoMirror = false,
    "M9.56 8l-2 -2l-4.15 -4.14L2 3.27L4.73 6L4 6c-0.55 0 -1 0.45 -1 1v10c0 0.55 0.45 1 1 1h12" +
        "c0.21 0 0.39 -0.08 0.55 -0.18L19.73 21l1.41 -1.41l-8.86 -8.86L9.56 8ZM5 16L5 8h1.73l8 8" +
        "L5 16ZM15 8v2.61l6 6L21 6.5l-4 4L17 7c0 -0.55 -0.45 -1 -1 -1h-5.61l2 2L15 8Z",
)

val Icons.Outlined.ViewAgenda: ImageVector
    get() = ViewAgendaIcon

private val ViewAgendaIcon = icon(
    "Outlined.ViewAgenda",
    autoMirror = false,
    "M19 13H5c-1.1 0 -2 0.9 -2 2v4c0 1.1 0.9 2 2 2h14c1.1 0 2 -0.9 2 -2v-4" +
        "C21 13.9 20.1 13 19 13ZM19 19H5v-4h14V19Z",
    "M19 3H5C3.9 3 3 3.9 3 5v4c0 1.1 0.9 2 2 2h14c1.1 0 2 -0.9 2 -2V5C21 3.9 20.1 3 19 3Z" +
        "M19 9H5V5h14V9Z",
)

val Icons.Outlined.VolumeOff: ImageVector
    get() = VolumeOffIcon

private val VolumeOffIcon = icon(
    "Outlined.VolumeOff",
    autoMirror = false,
    "M4.34 2.93L2.93 4.34L7.29 8.7L7 9L3 9v6h4l5 5v-6.59l4.18 4.18" +
        "c-0.65 0.49 -1.38 0.88 -2.18 1.11v2.06c1.34 -0.3 2.57 -0.92 3.61 -1.75l2.05 2.05" +
        "l1.41 -1.41L4.34 2.93ZM10 15.17L7.83 13L5 13v-2h2.83l0.88 -0.88L10 11.41v3.76ZM19 12" +
        "c0 0.82 -0.15 1.61 -0.41 2.34l1.53 1.53c0.56 -1.17 0.88 -2.48 0.88 -3.87" +
        "c0 -4.28 -2.99 -7.86 -7 -8.77v2.06c2.89 0.86 5 3.54 5 6.71ZM12 4l-1.88 1.88L12 7.76Z" +
        "M16.5 12c0 -1.77 -1.02 -3.29 -2.5 -4.03v1.79l2.48 2.48c0.01 -0.08 0.02 -0.16 0.02 -0.24Z",
)

val Icons.Outlined.VolumeUp: ImageVector
    get() = VolumeUpIcon

private val VolumeUpIcon = icon(
    "Outlined.VolumeUp",
    autoMirror = false,
    "M3 9v6h4l5 5L12 4L7 9L3 9ZM10 8.83v6.34L7.83 13L5 13v-2h2.83L10 8.83ZM16.5 12" +
        "c0 -1.77 -1.02 -3.29 -2.5 -4.03v8.05c1.48 -0.73 2.5 -2.25 2.5 -4.02ZM14 3.23v2.06" +
        "c2.89 0.86 5 3.54 5 6.71s-2.11 5.85 -5 6.71v2.06c4.01 -0.91 7 -4.49 7 -8.77" +
        "c0 -4.28 -2.99 -7.86 -7 -8.77Z",
)

val Icons.Outlined.WarningAmber: ImageVector
    get() = WarningAmberIcon

private val WarningAmberIcon = icon(
    "Outlined.WarningAmber",
    autoMirror = false,
    "M12 5.99L19.53 19L4.47 19L12 5.99M12 2L1 21h22L12 2ZM13 16h-2v2h2v-2ZM13 10h-2v4h2v-4Z",
)

val Icons.AutoMirrored.Outlined.HelpOutline: ImageVector
    get() = AutoMirroredHelpOutlineIcon

private val AutoMirroredHelpOutlineIcon = icon(
    "AutoMirrored.Outlined.HelpOutline",
    autoMirror = true,
    "M11 18h2v-2h-2v2ZM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2Z" +
        "M12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8ZM12 6" +
        "c-2.21 0 -4 1.79 -4 4h2c0 -1.1 0.9 -2 2 -2s2 0.9 2 2c0 2 -3 1.75 -3 5h2" +
        "c0 -2.25 3 -2.5 3 -5c0 -2.21 -1.79 -4 -4 -4Z",
)

val Icons.AutoMirrored.Outlined.VolumeOff: ImageVector
    get() = AutoMirroredVolumeOffIcon

private val AutoMirroredVolumeOffIcon = icon(
    "AutoMirrored.Outlined.VolumeOff",
    autoMirror = true,
    "M4.34 2.93L2.93 4.34L7.29 8.7L7 9L3 9v6h4l5 5v-6.59l4.18 4.18" +
        "c-0.65 0.49 -1.38 0.88 -2.18 1.11v2.06c1.34 -0.3 2.57 -0.92 3.61 -1.75l2.05 2.05" +
        "l1.41 -1.41L4.34 2.93ZM10 15.17L7.83 13L5 13v-2h2.83l0.88 -0.88L10 11.41v3.76ZM19 12" +
        "c0 0.82 -0.15 1.61 -0.41 2.34l1.53 1.53c0.56 -1.17 0.88 -2.48 0.88 -3.87" +
        "c0 -4.28 -2.99 -7.86 -7 -8.77v2.06c2.89 0.86 5 3.54 5 6.71ZM12 4l-1.88 1.88L12 7.76Z" +
        "M16.5 12c0 -1.77 -1.02 -3.29 -2.5 -4.03v1.79l2.48 2.48c0.01 -0.08 0.02 -0.16 0.02 -0.24Z",
)

val Icons.AutoMirrored.Outlined.VolumeUp: ImageVector
    get() = AutoMirroredVolumeUpIcon

private val AutoMirroredVolumeUpIcon = icon(
    "AutoMirrored.Outlined.VolumeUp",
    autoMirror = true,
    "M3 9v6h4l5 5L12 4L7 9L3 9ZM10 8.83v6.34L7.83 13L5 13v-2h2.83L10 8.83ZM16.5 12" +
        "c0 -1.77 -1.02 -3.29 -2.5 -4.03v8.05c1.48 -0.73 2.5 -2.25 2.5 -4.02ZM14 3.23v2.06" +
        "c2.89 0.86 5 3.54 5 6.71s-2.11 5.85 -5 6.71v2.06c4.01 -0.91 7 -4.49 7 -8.77" +
        "c0 -4.28 -2.99 -7.86 -7 -8.77Z",
)
