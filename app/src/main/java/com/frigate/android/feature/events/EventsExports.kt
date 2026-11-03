package com.frigate.android.feature.events

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

@Composable
internal fun EventsExports(session: EventsSessionHandle, interaction: EventsInteraction, profile: ServerProfile, cookie: String, onUnauthorized: () -> Unit) {
    val context = LocalContext.current
    val owner = context as LifecycleOwner
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val queued by interaction.exportJobs.collectAsState()
    val monitor = remember(session) { EventsExportMonitor(session.repository, session.scope, { unauthorized() }) }
    val jobs by monitor.jobs.collectAsState()
    val failed by monitor.failed.collectAsState()
    var visible by remember(session) { mutableStateOf(false) }
    var busy by remember(session) { mutableStateOf(false) }
    var pendingFile by remember(session) { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = pendingFile
        pendingFile = null
        if (file == null) { busy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: throw IOException("Could not open destination")
                    }
                    session.controller.showMessage("Video saved.")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { session.controller.showMessage("Could not save video. Please retry.") }
            finally { file.delete(); busy = false }
        }
    }
    LaunchedEffect(queued) {
        monitor.watch(queued)
        if (queued.isNotEmpty()) visible = true
    }
    DisposableEffect(monitor, owner, visible) {
        fun update() = monitor.setActive(visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer); monitor.setActive(false) }
    }
    DisposableEffect(session) { onDispose { pendingFile?.delete(); pendingFile = null } }
    if (jobs.isNotEmpty()) TextButton(onClick = { visible = true }) { Text("Exports (${jobs.size})") }
    if (visible && jobs.isNotEmpty()) EventsExportJobs(jobs, failed, busy, { visible = false }, monitor::retry, { job ->
        val path = job.downloadPath
        if (!busy && path != null) {
            busy = true
            scope.launch {
                var file: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        file = File.createTempFile("frigate-export-", ".mp4", context.cacheDir)
                        file!!.outputStream().use { EventsExportTransport(profile, cookie).download(path, it) }
                    }
                    pendingFile = file
                    launcher.launch("Frigate-export.mp4")
                    file = null
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    session.controller.showMessage("Could not download export. Please retry.")
                    if (error is FrigateHttpException && error.code == 401) unauthorized()
                } finally {
                    file?.delete()
                    if (pendingFile == null) busy = false
                }
            }
        }
    })
}
