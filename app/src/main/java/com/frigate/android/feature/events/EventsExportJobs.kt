package com.frigate.android.feature.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun EventsExportJobs(jobs: List<ExportJob>, failed: Boolean, busy: Boolean, onDismiss: () -> Unit, onRetry: () -> Unit, onSave: (ExportJob) -> Unit) {
    MaterialTheme(colorScheme = eventsColorScheme) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Exports") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) TextButton(onClick = onRetry) { Text("Could not refresh export status. Retry") }
                if (busy) { CircularProgressIndicator(); Text("Preparing video…") }
                jobs.forEachIndexed { index, job ->
                    Text("Export ${index + 1}: ${job.status}")
                    if (job.status == "completed" && job.downloadPath != null) TextButton(enabled = !busy, onClick = { onSave(job) }) { Text("Save video") }
                    else if (job.status == "completed") Text("File unavailable")
                }
            } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
}
