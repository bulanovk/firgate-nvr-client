package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.frigate.android.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val eventsColorScheme = darkColorScheme(
    primary = Color(0xFF93C5FD), background = Color.Black, surface = Color(0xFF090909),
    surfaceVariant = Color(0xFF262626), onSurface = Color(0xFFE8E8E8), onBackground = Color(0xFFE8E8E8),
)

/**
 * The PWA shows the type names and the filter labels from its md breakpoint up, so the wide header
 * keeps the labelled form only from that width on.
 */
private val eventsLabelledHeaderWidth = 768.dp

/**
 * Room the wide type tabs need for the severity dot and the "Alerts ∙ 206" label. The tabs fall
 * back to the bare dot when the filter buttons leave less space than that.
 */
private val eventsLabelledTabsWidth = 300.dp

/** Keeps a labelled filter from pushing the rest of the header out of the row. */
private val eventsActionLabelMaxWidth = 96.dp

/** The three forms the reference header gives the type tabs. */
private enum class EventsTabForm { Count, Dot, Labelled }

@Composable
internal fun EventsButton(description: String, icon: ImageVector, onClick: () -> Unit, text: String? = null,
    active: Boolean = false) {
    Box(
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = if (text == null) 40.dp else 48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.height(36.dp).clip(RoundedCornerShape(6.dp))
                .background(if (active) Color(0xFF404B83) else Color(0xFF1F1F1F)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = Color(0xFFE8E8E8))
            text?.let {
                Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFE8E8E8),
                    modifier = Modifier.widthIn(max = eventsActionLabelMaxWidth))
            }
        }
    }
}

/** The reference shows the reviewed filter as a checkbox with its label on the wide header. */
@Composable
private fun EventsSwitch(description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1F1F1F))
            .semantics { contentDescription = description }
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF526EF5), uncheckedColor = Color(0xFF555555)))
        Text(description, fontSize = 12.sp, color = Color(0xFFE8E8E8), maxLines = 1)
    }
}

@Composable
private fun EventsTypeTab(type: EventsType, selected: Boolean, counts: Pair<Int, Int>?, form: EventsTabForm,
    onClick: () -> Unit) {
    val label = when (type) {
        EventsType.ALERT -> "Alerts"
        EventsType.DETECTION -> "Detections"
        EventsType.MOTION -> "Motion"
    }
    val severity = when (type) {
        EventsType.ALERT -> Color(0xFFEF4444)
        EventsType.DETECTION -> Color(0xFFEA580C)
        EventsType.MOTION -> Color(0xFFF7B908)
    }
    val count = when (type) {
        EventsType.ALERT -> counts?.first
        EventsType.DETECTION -> counts?.second
        EventsType.MOTION -> null
    }
    val description = if (type == EventsType.MOTION) label else "$label, ${count ?: "loading"}"
    Box(
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .semantics { contentDescription = description; this.selected = selected; role = Role.Tab }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.height(36.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selected) Color(0xFF262626) else Color.Transparent).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (form) {
                EventsTabForm.Count -> when (type) {
                    EventsType.MOTION -> Icon(Icons.Outlined.GraphicEq, null, tint = severity,
                        modifier = Modifier.size(24.dp).rotate(90f))
                    else -> if (count == null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(count.toString(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = severity)
                }
                EventsTabForm.Dot -> Box(Modifier.size(8.dp).background(severity, CircleShape))
                EventsTabForm.Labelled -> {
                    Box(Modifier.size(8.dp).background(severity, CircleShape))
                    Text(if (type == EventsType.MOTION || count == null) label else "$label ∙ $count",
                        fontSize = 14.sp, maxLines = 1,
                        color = if (selected) Color(0xFFE8E8E8) else Color(0xFFA3A3A3))
                    if (type != EventsType.MOTION && count == null) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EventsHeader(state: EventsUiState, expanded: Boolean, catalog: EventsCatalog,
    onAction: (EventsAction) -> Unit, onDrawer: (String) -> Unit,
    motionOnly: Boolean = false, onMotionOnly: () -> Unit = {}) {
    val counts = state.summary?.let { reviewCounts(it, state.query.range.dayKey, state.filter.showReviewed) }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelled = expanded && maxWidth >= eventsLabelledHeaderWidth * fontScale
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.selectedIds.isNotEmpty()) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.selectedIds.size} selected", fontSize = 12.sp)
                        TextButton(onClick = { onAction(EventsAction.ClearSelection) }) { Text("Unselect", fontSize = 12.sp) }
                    }
                    EventsButton("Export", Icons.Outlined.FileDownload, { onAction(EventsAction.ExportSelection) },
                        if (labelled) "Export" else null)
                    EventsButton("Mark selected", Icons.Outlined.DoneAll, {
                        val selected = state.items.filter { it.id in state.selectedIds }
                        onAction(EventsAction.MarkViewed(selected.map { it.id }, !selected.all { it.hasBeenReviewed }))
                    }, if (labelled) "Mark as reviewed" else null)
                    if (catalog.role == "admin") EventsButton("Delete", Icons.Outlined.Delete,
                        { onAction(EventsAction.DeleteSelection) }, if (labelled) "Delete" else null)
                } else {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val form = when {
                            !expanded -> EventsTabForm.Count
                            labelled && maxWidth >= eventsLabelledTabsWidth * fontScale -> EventsTabForm.Labelled
                            else -> EventsTabForm.Dot
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            EventsType.entries.forEach { type ->
                                EventsTypeTab(type, state.filter.type == type, counts, form) {
                                    onAction(EventsAction.SelectType(type))
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EventsButton("Cameras", Icons.Outlined.Videocam, { onDrawer("cameras") },
                            if (labelled) "Cameras" else null)
                        if (state.filter.type == EventsType.MOTION) {
                            EventsButton("Motion only", Icons.Outlined.GraphicEq, onMotionOnly,
                                if (labelled) "Motion only" else null, motionOnly)
                        } else if (labelled) {
                            EventsSwitch("Show reviewed", state.filter.showReviewed) {
                                onAction(EventsAction.SetReviewedFilter(it))
                            }
                        } else {
                            EventsButton("Show reviewed", Icons.Outlined.Done, {
                                onAction(EventsAction.SetReviewedFilter(!state.filter.showReviewed))
                            }, null, state.filter.showReviewed)
                        }
                        if (expanded) {
                            EventsButton("Date", Icons.Outlined.CalendarMonth, { onDrawer("date") },
                                if (labelled) (state.query.range.dayKey ?: "Last 24 Hours") else null)
                            // Motion has no object labels or zones, so the reference keeps the panel away from it.
                            if (state.filter.type != EventsType.MOTION) EventsButton("Filters", Icons.Outlined.FilterAlt, { onDrawer("filters") },
                                if (labelled) "Filters" else null)
                        } else EventsButton("Settings", Icons.Outlined.Settings, { onDrawer("settings") })
                    }
                }
            }
        }
    }
}
