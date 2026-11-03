package com.frigate.android.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppIconsTest {
    private val outlined: Map<String, ImageVector> = mapOf(
        "CalendarMonth" to Icons.Outlined.CalendarMonth,
        "CameraAlt" to Icons.Outlined.CameraAlt,
        "DirectionsCar" to Icons.Outlined.DirectionsCar,
        "DoneAll" to Icons.Outlined.DoneAll,
        "Download" to Icons.Outlined.Download,
        "Event" to Icons.Outlined.Event,
        "FileDownload" to Icons.Outlined.FileDownload,
        "FilterAlt" to Icons.Outlined.FilterAlt,
        "Forward10" to Icons.Outlined.Forward10,
        "Fullscreen" to Icons.Outlined.Fullscreen,
        "FullscreenExit" to Icons.Outlined.FullscreenExit,
        "GraphicEq" to Icons.Outlined.GraphicEq,
        "GridOn" to Icons.Outlined.GridOn,
        "History" to Icons.Outlined.History,
        "LocalShipping" to Icons.Outlined.LocalShipping,
        "Pause" to Icons.Outlined.Pause,
        "Pets" to Icons.Outlined.Pets,
        "PhotoCamera" to Icons.Outlined.PhotoCamera,
        "Remove" to Icons.Outlined.Remove,
        "Replay10" to Icons.Outlined.Replay10,
        "Speed" to Icons.Outlined.Speed,
        "Today" to Icons.Outlined.Today,
        "Tune" to Icons.Outlined.Tune,
        "TwoWheeler" to Icons.Outlined.TwoWheeler,
        "Videocam" to Icons.Outlined.Videocam,
        "VideocamOff" to Icons.Outlined.VideocamOff,
        "ViewAgenda" to Icons.Outlined.ViewAgenda,
        "VolumeOff" to Icons.Outlined.VolumeOff,
        "VolumeUp" to Icons.Outlined.VolumeUp,
        "WarningAmber" to Icons.Outlined.WarningAmber,
    )
    private val mirrored: Map<String, ImageVector> = mapOf(
        "HelpOutline" to Icons.AutoMirrored.Outlined.HelpOutline,
        "VolumeOff" to Icons.AutoMirrored.Outlined.VolumeOff,
        "VolumeUp" to Icons.AutoMirrored.Outlined.VolumeUp,
    )

    @Test
    fun everyIconIsA24dpMaterialGlyphWithPaths() {
        (outlined + mirrored.mapKeys { "AutoMirrored.${it.key}" }).forEach { (name, icon) ->
            assertEquals(24.dp, icon.defaultWidth, name)
            assertEquals(24.dp, icon.defaultHeight, name)
            assertEquals(24f, icon.viewportWidth, name)
            assertEquals(24f, icon.viewportHeight, name)
            val paths = icon.root.filterIsInstance<VectorPath>()
            assertTrue(paths.isNotEmpty(), "$name has no path")
            paths.forEach { assertTrue(it.pathData.size > 2, "$name has an empty path") }
        }
    }

    @Test
    fun onlyTheAutoMirroredSetFlipsInRightToLeft() {
        outlined.forEach { (name, icon) -> assertFalse(icon.autoMirror, name) }
        mirrored.forEach { (name, icon) -> assertTrue(icon.autoMirror, name) }
    }

    @Test
    fun iconsAreCachedAndNotRebuiltOnEveryAccess() {
        assertTrue(Icons.Outlined.Videocam === Icons.Outlined.Videocam)
        assertTrue(Icons.AutoMirrored.Outlined.VolumeUp === Icons.AutoMirrored.Outlined.VolumeUp)
    }
}
