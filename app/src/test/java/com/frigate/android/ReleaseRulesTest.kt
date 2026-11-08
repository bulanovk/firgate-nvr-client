package com.frigate.android

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The release build shrinks and renames the fields of the classes. Gson reads the saved home networks
 * by the field names, so the class must be kept: otherwise the SSID comes back null and the settings
 * screen crashes on it.
 */
class ReleaseRulesTest {
    @Test
    fun homeNetworkKeepsItsFieldNamesForGson() {
        val rules = File(projectDirectory(), "proguard-rules.pro").readText()
        assertTrue(
            rules.contains("-keep class com.frigate.android.core.server.HomeNetwork { *; }"),
            "proguard-rules.pro must keep HomeNetwork, which Gson reads by field name",
        )
    }

    private fun projectDirectory(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "app/proguard-rules.pro")
            if (candidate.isFile) return directory.resolve("app")
            val local = File(directory, "proguard-rules.pro")
            if (local.isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("proguard-rules.pro not found from ${File("").absolutePath}")
    }
}
