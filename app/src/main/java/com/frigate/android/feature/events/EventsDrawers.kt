package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.frigate.android.ui.icons.CalendarMonth
import com.frigate.android.ui.icons.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset

/** The reference puts the filter panel in a centred dialog once the layout is wide. */
private val eventsWideDialogWidth = 420.dp

/** The panel edits labels, zones and the severity switch together: one Apply is one filter change. */
private data class EventsFilterDraft(
    val labels: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val includeOtherSeverity: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventsDrawers(drawer: String?, state: EventsUiState, catalog: EventsCatalog,
    onDrawer: (String?) -> Unit, onAction: (EventsAction) -> Unit, expanded: Boolean = false) {
    if (drawer == null) return
    if (drawer == "date") {
        EventsDatePicker(onDismiss = { onDrawer(null) },
            onConfirm = { millis ->
                onAction(EventsAction.SetDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()))
                onDrawer(null)
            },
            onLastDay = { onAction(EventsAction.SetDate(null)); onDrawer(null) })
        return
    }
    if (expanded) {
        var panel by remember { mutableStateOf(Rect.Zero) }
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput(drawer) {
            detectTapGestures { offset -> if (!panel.contains(offset)) onDrawer(null) }
        }, contentAlignment = Alignment.Center) {
            Column(Modifier.width(eventsWideDialogWidth).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF090909)).border(1.dp, Color(0xFF414141), RoundedCornerShape(12.dp))
                .onGloballyPositioned { panel = it.boundsInParent() }
                .semantics { contentDescription = "Filter dialog" }) {
                EventsDrawerContent(drawer, state, catalog, onDrawer, onAction)
            }
        }
        return
    }
    ModalBottomSheet(onDismissRequest = { onDrawer(null) }, containerColor = EventsSheetColors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EventsDrawerContent(drawer, state, catalog, onDrawer, onAction)
    }
}

/**
 * The app speaks English while the platform date picker follows the device locale, so the strings
 * the app controls (title and headline) are given explicitly. Month and weekday names still come
 * from the device locale: Material3 reads them from the JVM default locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventsDatePicker(onDismiss: () -> Unit, onConfirm: (Long) -> Unit, onLastDay: () -> Unit) {
    val date = rememberDatePickerState()
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = { date.selectedDateMillis?.let(onConfirm) },
            enabled = date.selectedDateMillis != null) { Text("Apply") }
    }, dismissButton = { TextButton(onClick = onLastDay) { Text("Last 24 Hours") } }) {
        DatePicker(date, title = { Text("Select date", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            headline = { Text("Selected date",
                Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)) })
    }
}

@Composable
private fun EventsDrawerContent(drawer: String, state: EventsUiState, catalog: EventsCatalog,
    onDrawer: (String?) -> Unit, onAction: (EventsAction) -> Unit) {
    var draft by remember(drawer) {
        mutableStateOf(EventsFilterDraft(state.query.labels.toSet(), state.query.zones.toSet(), state.filter.includeOtherSeverity))
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        when (drawer) {
            "cameras" -> {
                val options = catalog.cameras.ifEmpty { state.query.cameraIds.associateWith { it } }
                var pending by remember(drawer) { mutableStateOf(state.query.cameraIds.toSet()) }
                Text("Cameras", style = MaterialTheme.typography.titleMedium)
                if (options.isEmpty()) Text("No cameras available", modifier = Modifier.padding(vertical = 16.dp))
                options.forEach { (id, name) ->
                    EventsOptionRow(name, id in pending, { checked ->
                        pending = if (checked) pending + id else pending - id
                    }, "Camera $name", checkbox = true)
                }
                EventsSheetActions({ onDrawer(null) }, {
                    onAction(EventsAction.SetCameras(pending.toList()))
                    onDrawer(null)
                })
            }
            "filters" -> {
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                EventsGeneralFilters(state, draft) { draft = it }
                EventsSheetActions({ onDrawer(null) }, {
                    onAction(EventsAction.SetFilters(draft.labels.toList(), draft.zones.toList(), draft.includeOtherSeverity))
                    onDrawer(null)
                })
            }
            else -> EventsSettingsMenu(state, catalog, onDrawer, onAction)
        }
    }
}

/** Mobile review settings of the reference: a menu with Calendar and Filter, each opening its own view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventsSettingsMenu(state: EventsUiState, catalog: EventsCatalog,
    onDrawer: (String?) -> Unit, onAction: (EventsAction) -> Unit) {
    var view by remember { mutableStateOf("select") }
    val motion = state.filter.type == EventsType.MOTION
    var draft by remember { mutableStateOf(EventsFilterDraft()) }
    when (view) {
        "calendar" -> {
            EventsSettingsHead("Calendar") { view = "select" }
            val date = rememberDatePickerState()
            LaunchedEffect(date.selectedDateMillis) {
                val millis = date.selectedDateMillis ?: return@LaunchedEffect
                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                if (day != state.query.range.dayKey) onAction(EventsAction.SetDate(day))
            }
            DatePicker(date, title = null, headline = null, showModeToggle = false)
            HorizontalDivider()
            EventsActionRow(listOf("Reset" to { date.selectedDateMillis = null; onAction(EventsAction.SetDate(null)) }))
        }
        "filter" -> {
            EventsSettingsHead("Filter") { view = "select" }
            EventsGeneralFilters(state, draft) { draft = it }
            HorizontalDivider()
            EventsActionRow(listOf(
                "Apply" to {
                    onAction(EventsAction.SetFilters(draft.labels.toList(), draft.zones.toList(), draft.includeOtherSeverity))
                    view = "select"
                },
                "Reset" to {
                    draft = EventsFilterDraft()
                    onAction(EventsAction.SetFilters(emptyList(), emptyList(), false))
                },
            ))
        }
        else -> Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EventsMenuButton("Calendar", Icons.Outlined.CalendarMonth, active = state.query.range.dayKey != null) { view = "calendar" }
            if (!motion) EventsMenuButton("Filter", Icons.Outlined.FilterAlt,
                active = state.query.labels.isNotEmpty() || state.query.zones.isNotEmpty() || state.filter.includeOtherSeverity) {
                draft = EventsFilterDraft(state.query.labels.toSet(), state.query.zones.toSet(), state.filter.includeOtherSeverity)
                view = "filter"
            }
        }
    }
}

/** Colours of the approved mockup (`docs/design/pwa-events-tab.html`), not the app theme. */
private object EventsSheetColors {
    val surface = Color(0xFF090909)
    val button = Color(0xFF1F1F1F)
    val active = Color(0xFF404B83)
    val ink = Color(0xFFE8E8E8)
    val muted = Color(0xFFA3A3A3)
    val link = Color(0xFF8B9CF4)
    val switchOn = Color(0xFF5A6BD1)
    val switchOff = Color(0xFF404040)
    val thumb = Color(0xFFE5E5E5)
}

/** Centred 44 dp text buttons of the mockup; the first action is the active one. */
@Composable
private fun EventsActionRow(actions: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        actions.forEachIndexed { index, (label, onClick) ->
            Button(onClick = onClick, modifier = Modifier.defaultMinSize(minWidth = 96.dp).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (index == 0 && actions.size > 1) EventsSheetColors.active else EventsSheetColors.button,
                    contentColor = EventsSheetColors.ink),
                shape = RoundedCornerShape(6.dp)) { Text(label, fontSize = 13.sp) }
        }
    }
}

/** Full-width 44 dp row with an icon, as the approved mockup draws the settings menu. */
@Composable
private fun EventsMenuButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (active) EventsSheetColors.active else EventsSheetColors.button,
            contentColor = EventsSheetColors.ink),
        shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
    }
}

@Composable
private fun EventsSettingsHead(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Text("Back", Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
                color = EventsSheetColors.link, fontSize = 13.sp)
        }
        Text(title, color = EventsSheetColors.muted, fontSize = 13.sp)
        Box(Modifier.weight(1f))
    }
}

/** Alerts/Detections, then the labels and zones that the loaded records actually contain. */
@Composable
private fun EventsGeneralFilters(state: EventsUiState, draft: EventsFilterDraft, onDraft: (EventsFilterDraft) -> Unit) {
    val options = remember(state.items) { eventsFilterOptions(state.items) }
    val detection = state.filter.type == EventsType.DETECTION
    val other = draft.includeOtherSeverity
    EventsOptionRow("Alerts", !detection || other,
        { onDraft(draft.copy(includeOtherSeverity = it)) }, if (detection) "Include other severity" else "Alerts", enabled = detection)
    EventsOptionRow("Detections", detection || other,
        { onDraft(draft.copy(includeOtherSeverity = it)) }, if (detection) "Detections" else "Include other severity", enabled = !detection)
    // The reference always renders both "All" rows; only the per-value rows depend on the records.
    HorizontalDivider()
    EventsOptionRow("All Labels", draft.labels.isEmpty(),
        { onDraft(draft.copy(labels = allOptionSelection(draft.labels, it))) }, "All labels")
    options.labels.forEach { label ->
        EventsOptionRow(eventsLabelTitle(label), label in draft.labels,
            { onDraft(draft.copy(labels = toggleFilterValue(draft.labels, label, it))) }, "Label $label")
    }
    HorizontalDivider()
    EventsOptionRow("All Zones", draft.zones.isEmpty(),
        { onDraft(draft.copy(zones = allOptionSelection(draft.zones, it))) }, "All zones")
    options.zones.forEach { zone ->
        EventsOptionRow(eventsLabelTitle(zone), zone in draft.zones,
            { onDraft(draft.copy(zones = toggleFilterValue(draft.zones, zone, it))) }, "Zone $zone")
    }
}

@Composable
private fun EventsOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String,
    enabled: Boolean = true, checkbox: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = EventsSheetColors.ink, fontSize = 13.sp)
        if (checkbox) Checkbox(checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = description })
        else Switch(checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.scale(0.65f).semantics { contentDescription = description },
            colors = SwitchDefaults.colors(
                checkedTrackColor = EventsSheetColors.switchOn, checkedBorderColor = Color.Transparent,
                checkedThumbColor = EventsSheetColors.thumb,
                uncheckedTrackColor = EventsSheetColors.switchOff, uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = EventsSheetColors.thumb,
                disabledCheckedTrackColor = EventsSheetColors.switchOn.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = EventsSheetColors.switchOff.copy(alpha = 0.5f),
                disabledCheckedThumbColor = EventsSheetColors.thumb.copy(alpha = 0.5f),
                disabledUncheckedThumbColor = EventsSheetColors.thumb.copy(alpha = 0.5f),
                disabledCheckedBorderColor = Color.Transparent, disabledUncheckedBorderColor = Color.Transparent,
            ))
    }
}

@Composable
private fun EventsSheetActions(onCancel: () -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(onClick = onApply) { Text("Apply") }
    }
}
