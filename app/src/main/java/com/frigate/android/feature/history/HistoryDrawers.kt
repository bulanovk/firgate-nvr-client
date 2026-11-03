package com.frigate.android.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.frigate.android.ui.icons.CalendarMonth
import com.frigate.android.ui.icons.Download
import com.frigate.android.ui.icons.FilterAlt
import com.frigate.android.ui.icons.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import com.frigate.android.ui.icons.Today
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateCamera
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The drawer that is open over the archive view. */
internal sealed interface HistoryDrawer {
    data object None : HistoryDrawer
    data object Camera : HistoryDrawer
    data object Panel : HistoryDrawer
    data object Settings : HistoryDrawer
    data class Notice(val title: String, val description: String) : HistoryDrawer
}

/**
 * The page of the settings sheet. The PWA replaces the content of the sheet in place and offers a
 * back button to the menu of the settings, so the Android sheet does the same.
 */
internal sealed interface HistorySettingsPage {
    data object Menu : HistorySettingsPage
    data object Calendar : HistorySettingsPage
    data object Filter : HistorySettingsPage
}

/** The drawer of the cameras of the server; the choice keeps the moment of the day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryCameraDrawer(
    cameras: List<FrigateCamera>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    HistorySheet(onDismiss) {
        HistorySheetTitle("Choose a camera")
        if (cameras.isEmpty()) {
            HistorySheetDescription("The server has no cameras.")
        }
        cameras.forEach { camera ->
            HistorySheetOption(
                text = camera.name.ifBlank { camera.id },
                selected = camera.id == selected,
                onClick = { onSelect(camera.id) },
            )
        }
    }
}

/** The drawer of the panel of the archive: the timeline, the events or the detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPanelDrawer(
    selected: HistoryPanelMode,
    onSelect: (HistoryPanelMode) -> Unit,
    onDismiss: () -> Unit,
) {
    HistorySheet(onDismiss) {
        HistorySheetTitle("Viewing mode")
        HistoryPanelMode.entries.forEach { mode ->
            HistorySheetOption(
                text = mode.label(),
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

/** The menu of the viewing settings with its calendar and its filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySettingsDrawer(
    page: HistorySettingsPage,
    range: HistoryRange,
    initialRange: HistoryRange,
    zone: ZoneId,
    filter: HistoryFilter,
    labels: List<String>,
    onPage: (HistorySettingsPage) -> Unit,
    onSelectRange: (HistoryRange) -> Unit,
    onApplyFilter: (HistoryFilter) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onMotionSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (page) {
        HistorySettingsPage.Menu -> HistorySheet(onDismiss) {
            HistorySheetTitle("Viewing settings")
            HistorySettingsButton(Icons.Outlined.Download, "Export", onExport)
            HistorySettingsButton(Icons.Outlined.CalendarMonth, "Calendar") {
                onPage(HistorySettingsPage.Calendar)
            }
            HistorySettingsButton(Icons.Outlined.FilterAlt, "Filter") {
                onPage(HistorySettingsPage.Filter)
            }
            HistorySettingsButton(Icons.Outlined.Share, "Share moment", onShare)
            HistorySettingsButton(Icons.Outlined.Search, "Motion search", onMotionSearch)
            Spacer(Modifier.height(12.dp))
            HistorySheetDescription(historyRangeLabel(range, initialRange, zone))
        }

        HistorySettingsPage.Calendar -> HistoryCalendarPage(
            range = range,
            initialRange = initialRange,
            zone = zone,
            onPage = onPage,
            onSelectRange = onSelectRange,
            onDismiss = onDismiss,
        )

        HistorySettingsPage.Filter -> HistoryFilterPage(
            filter = filter,
            labels = labels,
            onPage = onPage,
            onApplyFilter = onApplyFilter,
            onDismiss = onDismiss,
        )
    }
}

/** The page of the calendar: a day of the archive or the last 24 hours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryCalendarPage(
    range: HistoryRange,
    initialRange: HistoryRange,
    zone: ZoneId,
    onPage: (HistorySettingsPage) -> Unit,
    onSelectRange: (HistoryRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val initialMillis = remember(range.after, zone) {
        Instant.ofEpochSecond(range.after.toLong())
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    HistorySheet(onDismiss) {
        HistorySheetTitle("Calendar", onBack = { onPage(HistorySettingsPage.Menu) })
        HistorySheetDescription("The archive of one day. By default the last 24 hours.")
        HistorySettingsButton(Icons.Outlined.Today, "Choose a day") { showPicker = true }
        HistorySettingsButton(Icons.Outlined.History, "Last 24 hours") {
            onSelectRange(initialRange)
            onDismiss()
        }
        Spacer(Modifier.height(12.dp))
        HistorySheetDescription(historyRangeLabel(range, initialRange, zone))
    }

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            onSelectRange(HistoryRange.daySeconds(historyDateOf(millis), zone))
                        }
                        showPicker = false
                        onDismiss()
                    },
                ) { Text("Apply", color = HistoryColors.foreground) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel", color = HistoryColors.foreground)
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

/** The page of the filter of the events by their labels. */
@Composable
private fun HistoryFilterPage(
    filter: HistoryFilter,
    labels: List<String>,
    onPage: (HistorySettingsPage) -> Unit,
    onApplyFilter: (HistoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }

    HistorySheet(onDismiss) {
        HistorySheetTitle("Filter", onBack = { onPage(HistorySettingsPage.Menu) })
        HistoryFilterSwitch(
            text = "All labels",
            checked = draft.labels == null,
            onCheckedChange = { checked ->
                if (checked) draft = draft.copy(labels = null)
            },
        )
        labels.forEach { label ->
            val selected = draft.labels?.contains(label) == true
            HistoryFilterSwitch(
                text = label,
                checked = selected,
                onCheckedChange = { checked ->
                    val current = draft.labels ?: emptySet()
                    draft = draft.copy(
                        labels = when {
                            checked -> current + label
                            current.size > 1 -> current - label
                            else -> current
                        },
                    )
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { draft = HistoryFilter.reset() }) {
                Text("Reset", color = HistoryColors.foreground)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                onApplyFilter(HistoryFilter.apply(filter, draft))
                onDismiss()
            }) {
                Text("Apply", color = HistoryColors.foreground)
            }
        }
    }
}

/** The honest explanation of an action the archive view does not carry out. */
@Composable
internal fun HistoryNoticeDrawer(
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    HistorySheet(onDismiss) {
        HistorySheetTitle(title)
        HistorySheetDescription(description)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Close", color = HistoryColors.foreground) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = HistoryColors.background,
        contentColor = HistoryColors.foreground,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HistoryColors.line),
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun HistorySheetTitle(text: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to the settings", tint = HistoryColors.foreground)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            Modifier.weight(1f),
            color = HistoryColors.foreground,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun HistorySheetDescription(text: String) {
    Text(
        text,
        Modifier.padding(bottom = 12.dp),
        color = HistoryColors.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun HistorySheetOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) HistoryColors.panel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = HistoryColors.foreground, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistorySettingsButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(HistoryColors.panelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = HistoryColors.foreground, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = HistoryColors.foreground, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HistoryFilterSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            color = HistoryColors.foreground,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The name of a panel of the archive in the language of the app. */
internal fun HistoryPanelMode.label(): String = when (this) {
    HistoryPanelMode.TIMELINE -> "Timeline"
    HistoryPanelMode.EVENTS -> "Events"
    HistoryPanelMode.DETAIL -> "Detail"
}

/** The calendar day of a date picker value, which arrives in UTC. */
private fun historyDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The name of the window of the archive: the window of the session or the day of the calendar.
 *
 * A calendar day is as long as the last 24 hours, so the window of the session is recognized by
 * its bounds and not by its length.
 */
internal fun historyRangeLabel(range: HistoryRange, initialRange: HistoryRange, zone: ZoneId): String {
    if (range.after == initialRange.after && range.before == initialRange.before) return "Last 24 hours"
    val date = Instant.ofEpochSecond(range.after.toLong()).atZone(zone).toLocalDate()
    return date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}
