package com.frigate.android

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The session of the app lives in the composition of the activity: the socket of the activity, the
 * players of the cameras and the navigation of the archive. The activity must therefore survive a
 * configuration change on its own. If the system destroys and recreates it, everything of the
 * session is lost and the user lands on the grid of the live screen again.
 */
class MainActivityManifestTest {
    @Test
    fun mainActivityDeclaresPictureInPictureSupport() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())
        val activities = document.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttribute("android:name") == ".MainActivity" }
        assertTrue(main.getAttribute("android:supportsPictureInPicture") == "true")
    }

    @Test
    fun mainActivityHandlesTheConfigurationChangesItself() {
        val declared = configChangesOfMainActivity()
        val required = setOf(
            "orientation",
            "screenSize",
            "screenLayout",
            "smallestScreenSize",
            "keyboardHidden",
            "uiMode",
        )
        val missing = required - declared
        assertTrue(
            missing.isEmpty(),
            "MainActivity must handle $missing itself, but declares '${declared.joinToString("|")}'",
        )
    }

    @Test
    fun manifestDeclaresWhatHomeNetworkDetectionNeeds() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }.toSet()
        val required = setOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        assertTrue(declared.containsAll(required), "Missing permissions: ${required - declared}")

        val application = document.getElementsByTagName("application").item(0) as Element
        assertTrue(
            application.getAttribute("android:networkSecurityConfig") == "@xml/network_security_config",
            "The local http address of Frigate is blocked without a network security config",
        )
        assertTrue(File(manifestFile().parentFile, "res/xml/network_security_config.xml").isFile)
    }

    private fun configChangesOfMainActivity(): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifestFile())
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            if (activity.getAttribute("android:name") == ".MainActivity") {
                return activity.getAttribute("android:configChanges")
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }
        throw AssertionError("MainActivity is not declared in the manifest")
    }

    private fun manifestFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")) {
                val file = File(directory, candidate)
                if (file.isFile) return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("The manifest of the app was not found from ${File("").absolutePath}")
    }
}
