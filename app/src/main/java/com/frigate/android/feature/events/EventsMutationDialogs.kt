package com.frigate.android.feature.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun EventsDeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MaterialTheme(colorScheme = eventsColorScheme) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete recordings?") },
            text = { Column { Text("$count selected"); Text("This also deletes overlapping recordings. This cannot be undone.") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable
internal fun EventsExportDialog(count: Int, cases: List<ExportCase>, onDismiss: () -> Unit, onConfirm: (ExportCaseChoice?) -> Unit,
    allowCase: Boolean = false) {
    var choice by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = eventsColorScheme) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Export recordings") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$count selected")
                Text("Exports are queued on the server. The file is available after processing.")
                if (count > 50) Text("Select at most 50 events to export.", color = MaterialTheme.colorScheme.error)
                if (allowCase && count > 1) {
                    TextButton(onClick = { choice = null }) { Text(if (choice == null) "✓ No case" else "No case") }
                    cases.forEach { case -> TextButton(onClick = { choice = case.id }) { Text((if (choice == case.id) "✓ " else "") + case.name) } }
                    TextButton(onClick = { choice = "new" }) { Text(if (choice == "new") "✓ New case" else "New case") }
                    if (choice == "new") {
                        OutlinedTextField(name, { name = it }, label = { Text("Case name") })
                        OutlinedTextField(description, { description = it }, label = { Text("Description") })
                    }
                }
            } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            confirmButton = { TextButton(enabled = count in 1..50 && (choice != "new" || name.isNotBlank()), onClick = {
                onConfirm(when (choice) { null -> null; "new" -> ExportCaseChoice(name = name.trim(), description = description.trim().ifBlank { null }); else -> ExportCaseChoice(id = choice) })
            }) { Text("Export") } })
    }
}
