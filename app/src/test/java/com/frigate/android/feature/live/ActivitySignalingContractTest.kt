package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateCamera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivitySignalingContractTest {
    @Test
    fun activityFlowsFromEnvelopeToRecordingObjectAndPlaybackIndicators() {
        val camera = camera(recordEnabled = true)
        val initial = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "camera_activity",
                """{"street":{"motion":false,"objects":[],"config":{"record":true,"detect":true}},"yard":{"motion":false,"objects":[],"config":{"record":true,"detect":true}}}""",
            ),
        )
        val idle = initial.cameras.getValue("street")
        assertFalse(idle.activeRecording)
        assertEquals(RecordingIndicatorState.ENABLED, recordingIndicatorState(true, idle.activeRecording))

        val withPerson = event(initial, "start", "street", "p1", "person")
        val personActivity = withPerson.cameras.getValue("street")
        assertTrue(personActivity.activeRecording)
        assertEquals(RecordingIndicatorState.ACTIVE, recordingIndicatorState(true, personActivity.activeRecording))
        assertTrue(cameraIndicators(camera, personActivity).contains(CameraIndicator.Object("person")))
        assertEquals(CameraPlaybackAction.START, cameraPlaybackAction(true, personActivity.activeRecording, false))

        val withCar = event(withPerson, "start", "street", "c1", "car")
        val afterPersonEnds = event(withCar, "end", "street", "p1", "person")
        val carActivity = afterPersonEnds.cameras.getValue("street")
        assertTrue(carActivity.activeRecording)
        assertEquals(setOf("car"), carActivity.trackedObjects)
        assertEquals(RecordingIndicatorState.ACTIVE, recordingIndicatorState(true, carActivity.activeRecording))
        assertEquals(initial.cameras.getValue("yard"), afterPersonEnds.cameras.getValue("yard"))

        val afterCarEnds = event(afterPersonEnds, "end", "street", "c1", "car")
        val idleAgain = afterCarEnds.cameras.getValue("street")
        assertFalse(idleAgain.activeRecording)
        assertEquals(RecordingIndicatorState.ENABLED, recordingIndicatorState(true, idleAgain.activeRecording))
        assertEquals(CameraPlaybackAction.SNAPSHOT, cameraPlaybackAction(true, idleAgain.activeRecording, false))
    }

    @Test
    fun motionActivatesRecordingButStationaryObjectsDoNot() {
        val snapshot = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "camera_activity",
                """{"street":{"motion":false,"objects":[],"config":{"record":true,"detect":true}}}""",
            ),
        )
        val moving = CameraActivityReducer.reduce(
            snapshot,
            CameraActivityFixtures.envelope("street/motion", "true"),
        ).cameras.getValue("street")
        assertTrue(moving.activeRecording)
        assertEquals(RecordingIndicatorState.ACTIVE, recordingIndicatorState(true, moving.activeRecording))

        val stationary = event(snapshot, "start", "street", "p1", "person", stationary = true)
            .cameras.getValue("street")
        assertFalse(stationary.activeRecording)
        assertEquals(RecordingIndicatorState.ENABLED, recordingIndicatorState(true, stationary.activeRecording))
    }

    @Test
    fun disabledRecordingAndDetectionDoNotEraseTrackedObjects() {
        val camera = camera(recordEnabled = true, detectEnabled = true)
        val snapshot = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "camera_activity",
                """{"street":{"motion":false,"objects":[],"config":{"record":false,"detect":true}}}""",
            ),
        )
        val active = event(snapshot, "start", "street", "p1", "person")
        val activity = active.cameras.getValue("street")
        assertEquals(RecordingIndicatorState.DISABLED, recordingIndicatorState(false, activity.activeRecording))
        assertEquals(listOf(CameraIndicator.Object("person")), cameraIndicators(camera, activity))

        val detectDisabled = CameraActivityReducer.reduce(
            active,
            CameraActivityFixtures.envelope("street/detect/state", "\"OFF\""),
        ).cameras.getValue("street")
        assertEquals(setOf("person"), detectDisabled.trackedObjects)
        assertTrue(cameraIndicators(camera, detectDisabled).isEmpty())
    }

    private fun camera(recordEnabled: Boolean, detectEnabled: Boolean = true) = FrigateCamera(
        id = "street",
        name = "Street",
        enabled = true,
        recordEnabled = recordEnabled,
        detectEnabled = detectEnabled,
    )

    private fun event(
        previous: CameraActivityState,
        type: String,
        camera: String,
        id: String,
        label: String,
        stationary: Boolean = false,
    ): CameraActivityState = CameraActivityReducer.reduce(
        previous,
        CameraActivityFixtures.stringPayloadEnvelope(
            "events",
            """{"type":"$type","after":{"camera":"$camera","id":"$id","label":"$label","stationary":$stationary}}""",
        ),
    )
}
