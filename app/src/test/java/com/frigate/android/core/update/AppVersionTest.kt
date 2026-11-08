package com.frigate.android.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppVersionTest {
    @Test
    fun channelComesFromTheBuild() {
        assertEquals(UpdateChannel.Release, UpdateChannel.of("release"))
        assertEquals(UpdateChannel.Dev, UpdateChannel.of("dev"))
        assertEquals(UpdateChannel.None, UpdateChannel.of("none"))
        assertEquals(UpdateChannel.None, UpdateChannel.of(""))
    }

    @Test
    fun releaseIsNewerByItsSemanticVersion() {
        assertTrue(AppVersion.isNewer(UpdateChannel.Release, "0.0.2", release("v0.0.3")))
        assertTrue(AppVersion.isNewer(UpdateChannel.Release, "0.0.9", release("v0.0.10")))
        assertTrue(AppVersion.isNewer(UpdateChannel.Release, "0.9.9", release("1.0.0")))
        assertFalse(AppVersion.isNewer(UpdateChannel.Release, "0.0.2", release("v0.0.2")))
        assertFalse(AppVersion.isNewer(UpdateChannel.Release, "0.0.10", release("v0.0.9")))
    }

    @Test
    fun releaseWithAnUnreadableVersionIsNeverNewer() {
        assertFalse(AppVersion.isNewer(UpdateChannel.Release, "0.0.2", release("nightly")))
        assertFalse(AppVersion.isNewer(UpdateChannel.Release, "garbage", release("v0.0.3")))
    }

    @Test
    fun devBuildIsNewerByItsCiRunNumber() {
        assertEquals(12, AppVersion.devBuild("0.1.0-test.12+abc1234"))
        assertNull(AppVersion.devBuild("0.1.0"))
        assertTrue(AppVersion.isNewer(UpdateChannel.Dev, "0.1.0-test.12+abc1234", dev(13)))
        assertFalse(AppVersion.isNewer(UpdateChannel.Dev, "0.1.0-test.12+abc1234", dev(12)))
        assertFalse(AppVersion.isNewer(UpdateChannel.Dev, "0.1.0-test.12+abc1234", dev(11)))
    }

    @Test
    fun localBuildNeverUpdates() {
        assertFalse(AppVersion.isNewer(UpdateChannel.None, "0.1.0", release("v9.0.0")))
        assertFalse(AppVersion.isNewer(UpdateChannel.None, "0.1.0", dev(999)))
    }

    private fun release(tag: String) = AvailableUpdate(tag.removePrefix("v"), "https://example/app.apk", null, null)
    private fun dev(build: Int) = AvailableUpdate("0.1.0-test.$build+fff", "https://example/app.apk", null, build)
}
