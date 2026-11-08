package com.frigate.android.core.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.frigate.android.BuildConfig
import com.frigate.android.core.server.AndroidServerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The updates of the app as the screens see them: the state of the check and what the user can do. */
class AppUpdates(
    context: Context,
    /** Lives as long as the app screen, so leaving the settings does not stop a download. */
    private val scope: CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker(
        channel = UpdateChannel.of(BuildConfig.UPDATE_CHANNEL),
        installedVersion = BuildConfig.VERSION_NAME,
        feed = GitHubUpdateFeed(),
        preferences = UpdateSettings(AndroidServerPreferences(context, "frigate_updates")),
    ),
    private val installer: UpdateInstaller = UpdateInstaller(context),
) {
    private val context = context.applicationContext
    val version: String get() = checker.installedVersion
    val channel: UpdateChannel get() = checker.channel

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var autoCheck by mutableStateOf(checker.preferences.autoCheck)
        private set

    val updateAvailable: Boolean
        get() = state.let { it is UpdateState.Available || it is UpdateState.NeedsPermission }

    private var job: Job? = null
    private val busy get() = job?.isActive == true

    fun changeAutoCheck(enabled: Boolean) {
        checker.preferences.autoCheck = enabled
        autoCheck = enabled
    }

    fun checkIfDue() {
        if (!busy && checker.isDue() && !updateAvailable) check()
    }

    fun check() {
        if (busy) return
        job = scope.launch {
            state = UpdateState.Checking
            state = checker.check()
        }
    }

    /** Downloads and installs the offered update; asks for the install permission first when the app lacks it. */
    fun install() {
        val update = when (val current = state) {
            is UpdateState.Available -> current.update
            is UpdateState.NeedsPermission -> current.update
            else -> return
        }
        if (busy) return
        if (!installer.canInstall()) {
            state = UpdateState.NeedsPermission(update)
            context.startActivity(installer.permissionIntent())
            return
        }
        job = scope.launch {
            try {
                state = UpdateState.Downloading(update, null)
                val apk = installer.download(update) { percent -> state = UpdateState.Downloading(update, percent) }
                state = UpdateState.Installing(update)
                installer.install(apk)
                // On success the new app replaces this process; only a failure comes back.
                state = UpdateState.Failed(InstallResults.failure.filterNotNull().first())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = UpdateState.Failed("Could not update: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}
