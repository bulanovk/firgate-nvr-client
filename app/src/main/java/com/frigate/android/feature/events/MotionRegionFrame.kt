package com.frigate.android.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun MotionRegionFrame(sourceAspect: Double, crop: NormalizedRect?, heatmap: Map<Int, Double>, dimPercent: Int,
    content: @Composable () -> Unit) {
    val aspect = crop?.let { regionAspect(it, sourceAspect) } ?: sourceAspect
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(aspect.toFloat()).clipToBounds(), contentAlignment = Alignment.Center) {
        val geometry = motionCropGeometry(maxWidth.value.toDouble(), maxHeight.value.toDouble(), sourceAspect, crop)
        Box(Modifier.size(geometry.frameWidth.dp, geometry.frameHeight.dp).graphicsLayer {
            scaleX = geometry.scale.toFloat(); scaleY = scaleX
            translationX = geometry.translationX.dp.toPx(); translationY = geometry.translationY.dp.toPx()
        }) {
            content()
            if (heatmap.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                val maximum = heatmap.values.maxOrNull() ?: 0.0
                val cellWidth = size.width / 16
                val cellHeight = size.height / 16
                repeat(256) { cell ->
                    drawRect(Color.Black.copy(alpha = motionDimAlpha(heatmap[cell] ?: 0.0, maximum, dimPercent).toFloat()),
                        topLeft = Offset(cell % 16 * cellWidth, cell / 16 * cellHeight), size = Size(cellWidth, cellHeight))
                }
            }
        }
    }
}
