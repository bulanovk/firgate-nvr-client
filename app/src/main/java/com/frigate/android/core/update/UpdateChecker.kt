package com.frigate.android.core.update

import com.frigate.android.core.server.ServerPreferences
import kotlinx.coroutines.CancellationException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val percent: Int?) : UpdateState
    data class Installing(val update: AvailableUpdate) : UpdateState
    /** Android installs apps from this app only after the user allows it in the system settings. */
    data class NeedsPermission(val update: AvailableUpdate) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** The choice of the user and the time of the last successful check. */
class UpdateSettings(private val preferences: ServerPreferences) {
    var autoCheck: Boolean
        get() = preferences.get(AUTO_CHECK) != "false"
        set(value) = preferences.put(AUTO_CHECK, value.toString())

    var lastCheck: Long?
        get() = preferences.get(LAST_CHECK)?.toLongOrNull()
        set(value) {
            if (value == null) preferences.remove(LAST_CHECK) else preferences.put(LAST_CHECK, value.toString())
        }

    private companion object {
        const val AUTO_CHECK = "auto_check"
        const val LAST_CHECK = "last_check"
    }
}

/** Asks the channel of the build for a newer build: on its own at most every [interval], or when the user asks. */
class UpdateChecker(
    val channel: UpdateChannel,
    val installedVersion: String,
    private val feed: UpdateFeed,
    val preferences: UpdateSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interval: Long = 6 * 60 * 60 * 1000L,
) {
    fun isDue(): Boolean {
        if (channel == UpdateChannel.None || !preferences.autoCheck) return false
        val last = preferences.lastCheck ?: return true
        return clock() - last >= interval
    }

    suspend fun check(): UpdateState {
        if (channel == UpdateChannel.None) return UpdateState.Idle
        val remote = try {
            feed.latest(channel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return UpdateState.Failed("Could not check for updates: ${error.message ?: error.javaClass.simpleName}")
        }
        preferences.lastCheck = clock()
        return if (remote != null && AppVersion.isNewer(channel, installedVersion, remote)) {
            UpdateState.Available(remote)
        } else {
            UpdateState.UpToDate
        }
    }
}
