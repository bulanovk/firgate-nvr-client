package com.frigate.android.core.update

import com.frigate.android.core.server.ServerPreferences
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {
    private class MemoryPreferences : ServerPreferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private val hour = 3_600_000L
    private var now = 100 * hour
    private val preferences = MemoryPreferences()
    private var remote: AvailableUpdate? = AvailableUpdate("0.0.3", "https://dl/a.apk", null, null)
    private var requests = 0

    private fun checker(channel: UpdateChannel = UpdateChannel.Release, installed: String = "0.0.2") = UpdateChecker(
        channel = channel,
        installedVersion = installed,
        feed = { requests++; remote },
        preferences = UpdateSettings(preferences),
        clock = { now },
    )

    @Test
    fun firstCheckIsDueAndLaterOnlyAfterSixHours() = runBlocking {
        val checker = checker()
        assertTrue(checker.isDue())
        checker.check()
        assertFalse(checker.isDue())
        now += 6 * hour - 1
        assertFalse(checker.isDue())
        now += 1
        assertTrue(checker.isDue())
    }

    @Test
    fun autoCheckOffIsNeverDue() {
        val checker = checker()
        UpdateSettings(preferences).autoCheck = false
        assertFalse(checker.isDue())
        assertFalse(checker(channel = UpdateChannel.None).isDue())
    }

    @Test
    fun autoCheckIsOnByDefault() {
        assertTrue(UpdateSettings(preferences).autoCheck)
    }

    @Test
    fun newerVersionIsAvailable() = runBlocking {
        assertEquals(UpdateState.Available(remote!!), checker().check())
        remote = AvailableUpdate("0.0.2", "https://dl/a.apk", null, null)
        assertEquals(UpdateState.UpToDate, checker().check())
        remote = null
        assertEquals(UpdateState.UpToDate, checker().check())
    }

    @Test
    fun failedCheckReportsAndIsRetriedNextTime() = runBlocking {
        val failing = UpdateChecker(UpdateChannel.Release, "0.0.2", { throw java.io.IOException("offline") },
            UpdateSettings(preferences), { now })
        assertEquals(UpdateState.Failed("Could not check for updates: offline"), failing.check())
        assertTrue(failing.isDue())
    }

    @Test
    fun localBuildDoesNotAsk() = runBlocking {
        assertEquals(UpdateState.Idle, checker(channel = UpdateChannel.None).check())
        assertEquals(0, requests)
    }
}
