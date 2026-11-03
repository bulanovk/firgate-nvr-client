package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraActivityParserTest {
    @Test
    fun parsesMotionAndNonStationaryObjectsFromCameraActivitySnapshot() {
        val activity = CameraActivityParser.parse(
            """
            {"front_door":{"motion":true,"objects":[{"id":"1","stationary":false}],"config":{"enabled":true}}}
            """.trimIndent(),
        )

        assertTrue(activity.getValue("front_door").activeMotion)
        assertTrue(activity.getValue("front_door").activeTracking)
        assertEquals(setOf("object"), activity.getValue("front_door").trackedObjects)
    }

    @Test
    fun parsesRuntimeRecordingAndDetectionStatesFromSnapshotConfig() {
        val activity = CameraActivityParser.parse(
            """
            {"front_door":{"motion":false,"objects":[],"config":{"record":false,"detect":true}}}
            """.trimIndent(),
        ).getValue("front_door")

        assertFalse(activity.recordingEnabled == true)
        assertTrue(activity.detectionEnabled == true)
    }

    @Test
    fun stationaryObjectsRemainVisibleWithoutActivatingTracking() {
        val activity = CameraActivityParser.parse(
            """{"yard":{"motion":false,"objects":[{"id":"p1","label":"person","stationary":true}]}}""",
        ).getValue("yard")
        val camera = com.frigate.android.core.server.FrigateCamera("yard", "Yard", true)

        assertEquals(listOf(CameraIndicator.Object("person")), cameraIndicators(camera, activity))
        assertFalse(activity.activeTracking)
    }

    @Test
    fun disabledSnapshotSuppressesActivity() {
        val activity = CameraActivityParser.parse(
            """{"yard":{"motion":true,"objects":[{"id":"p1","label":"person"}],"config":{"enabled":false}}}""",
        ).getValue("yard")

        assertFalse(activity.activeMotion)
        assertFalse(activity.activeTracking)
    }

    @Test
    fun stationaryObjectsDoNotActivateLiveStream() {
        val activity = CameraActivityParser.parse(
            """{"front_door":{"motion":false,"objects":[{"id":"1","stationary":true}]}}""",
        )

        assertFalse(activity.getValue("front_door").activeMotion)
        assertFalse(activity.getValue("front_door").activeTracking)
    }
}
