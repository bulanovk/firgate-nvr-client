package com.frigate.android.core.update

/**
 * Where the build takes its updates from. The build sets it (`-PappUpdateChannel`): the tagged
 * release follows the GitHub releases, the test build of main follows the test builds, a local
 * build does not update itself.
 */
enum class UpdateChannel {
    Release, Dev, None;

    companion object {
        fun of(value: String): UpdateChannel = when (value.lowercase()) {
            "release" -> Release
            "dev" -> Dev
            else -> None
        }
    }
}

/** A build the channel offers. [build] is the CI run number of a test build, null for a release. */
data class AvailableUpdate(
    val versionName: String,
    val apkUrl: String,
    val notes: String?,
    val build: Int?,
)

/**
 * All builds share versionCode 1 (a growing code would block `adb install -r` of local builds), so
 * a newer build is told by its name: the semantic version of a release, the CI run number of a test build.
 */
object AppVersion {
    private val semver = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
    private val testBuild = Regex("""-test\.(\d+)(\+|$)""")

    fun devBuild(versionName: String): Int? = testBuild.find(versionName)?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(channel: UpdateChannel, installed: String, remote: AvailableUpdate): Boolean = when (channel) {
        UpdateChannel.Release -> {
            val current = release(installed)
            val offered = release(remote.versionName)
            current != null && offered != null && compare(offered, current) > 0
        }
        UpdateChannel.Dev -> {
            val current = devBuild(installed)
            val offered = remote.build
            current != null && offered != null && offered > current
        }
        UpdateChannel.None -> false
    }

    private fun release(name: String): List<Int>? =
        semver.find(name.trim())?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

    private fun compare(a: List<Int>, b: List<Int>): Int =
        a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
}
