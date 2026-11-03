package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionRegionSheet(selection: MotionRegionSelection, heatmap: Map<Int, Double>, onDismiss: () -> Unit, onApply: () -> Unit,
    sourceAspect: Float = 16f / 9, background: @Composable BoxScope.() -> Unit = {}) {
    var draft by remember(selection) { mutableStateOf(selection.pending) }
    var crop by remember(selection) { mutableStateOf(selection.crop) }
    var dim by remember(selection) { mutableIntStateOf(selection.dimPercent) }
    val available = heatmap.isNotEmpty()
    fun cancel() { selection.cancel(); draft = selection.pending; onDismiss() }
    MaterialTheme(colorScheme = eventsColorScheme) {
        ModalBottomSheet(onDismissRequest = { cancel() }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motion regions", style = MaterialTheme.typography.titleLarge)
                Text("Select cells to include motion in any selected area. Changes apply only after Apply.")
                val maximum = heatmap.values.maxOrNull() ?: 0.0
                Box(Modifier.fillMaxWidth().aspectRatio(sourceAspect)) {
                    background()
                    Column(Modifier.fillMaxSize()) {
                        repeat(16) { row -> Row(Modifier.weight(1f)) {
                            repeat(16) { column ->
                                val cell = row * 16 + column
                                val intensity = heatmap[cell] ?: 0.0
                                val alpha = if (maximum > 0) sqrt(intensity / maximum).toFloat().coerceIn(0f, 1f) * .6f else 0f
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .background(if (cell in draft) Color.White.copy(alpha = .4f) else Color(0xfff7b908).copy(alpha = alpha))
                                    .border(.5.dp, Color.White.copy(alpha = if (cell in draft) .9f else .2f))
                                    .semantics { contentDescription = "Region cell ${row + 1}, ${column + 1}"; selected = cell in draft }
                                    .clickable(enabled = available) { selection.toggle(cell); draft = selection.pending })
                            }
                        } }
                    }
                }
                if (heatmap.isEmpty()) Text("No heatmap data in this range. Region filtering is unavailable; other motion controls still work.")
                TextButton(enabled = available, onClick = { selection.clear(); draft = selection.pending }) { Text("Clear") }
                Row { Checkbox(crop, { crop = it }, enabled = available); Text("Crop to region") }
                Text("Dim background: $dim%")
                Slider(dim.toFloat(), { dim = it.toInt() }, valueRange = 0f..100f, enabled = available,
                    modifier = Modifier.semantics { contentDescription = "Dim background" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { cancel() }) { Text("Cancel") }
                    TextButton(enabled = available, onClick = { selection.crop = crop; selection.dimPercent = dim; selection.apply(); onApply() }) { Text("Apply") }
                }
            }
        }
    }
}
