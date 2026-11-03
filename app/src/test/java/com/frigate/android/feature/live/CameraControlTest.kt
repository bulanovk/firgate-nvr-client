package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateCamera
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraControlTest {
    @Test
    fun commandsUsePayloadAndNeverRetainTheRequest() {
        val command = JsonParser.parseString(CameraControl.RECORDING.command("yard", false)).asJsonObject
        assertEquals("yard/recordings/set", command["topic"].asString)
        assertEquals("OFF", command["payload"].asString)
        assertFalse(command["retain"].asBoolean)
        assertFalse(command.has("message"))
    }

    @Test
    fun onlyAdminsCanUseCameraControlsAndOptionalFeaturesRequireConfigSupport() {
        val camera = FrigateCamera("yard", "Yard", true)
        assertTrue(cameraControls(camera, "viewer").isEmpty())
        assertEquals(listOf(CameraControl.CAMERA, CameraControl.DETECTION, CameraControl.SNAPSHOTS), cameraControls(camera, "admin"))
        val capable = camera.copy(recordEnabledInConfig = true, audioEnabledInConfig = true,
            transcriptionEnabledInConfig = true, autotrackingEnabledInConfig = true)
        assertEquals(CameraControl.entries, cameraControls(capable, "admin"))
    }

    @Test
    fun reconnectSnapshotAndFeatureTopicsUpdateSettingsWithoutChangingMotion() {
        val snapshot = CameraActivityReducer.reduce(CameraActivityState(),
            """{"topic":"camera_activity","payload":{"yard":{"motion":true,"config":{"snapshots":true,"audio":false,"audio_transcription":true,"autotracking":false}}}}""")
        assertEquals(true, snapshot.cameras.getValue("yard").controls[CameraControl.SNAPSHOTS])
        assertEquals(false, snapshot.cameras.getValue("yard").controls[CameraControl.AUDIO_DETECTION])
        assertEquals(true, snapshot.cameras.getValue("yard").controls[CameraControl.TRANSCRIPTION])
        assertEquals(false, snapshot.cameras.getValue("yard").controls[CameraControl.AUTOTRACKING])
        val updated = CameraActivityReducer.reduce(snapshot,
            """{"topic":"yard/ptz_autotracker/state","payload":"ON"}""")
        assertEquals(true, updated.cameras.getValue("yard").controls[CameraControl.AUTOTRACKING])
        assertTrue(updated.cameras.getValue("yard").activeMotion)
        assertEquals(updated, CameraActivityReducer.reduce(updated,
            """{"topic":"yard/audio/state","payload":"unknown"}"""))
    }

    @Test
    fun cameraIdsCannotInjectCommandTopicSegments() {
        assertThrows(IllegalArgumentException::class.java) { CameraControl.CAMERA.command("yard/other", true) }
    }
}
