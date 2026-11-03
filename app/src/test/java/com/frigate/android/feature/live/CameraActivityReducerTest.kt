package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraActivityReducerTest {
    @Test
    fun envelopeRoutesOnlyToItsCamera() {
        val previous = CameraActivityState(
            runtimeByCamera = mapOf(
                "yard" to CameraRuntimeState(activeMotion = true),
            ),
        )

        val state = CameraActivityReducer.reduce(
            previous,
            CameraActivityFixtures.envelope("street/motion", "\"ON\""),
        )

        assertTrue(state.cameras.getValue("street").activeMotion)
        assertEquals(previous.cameras["yard"], state.cameras["yard"])
    }

    @Test
    fun malformedOrIncompleteEnvelopeDoesNotChangeState() {
        val previous = CameraActivityState(
            runtimeByCamera = mapOf("yard" to CameraRuntimeState(activeMotion = true)),
        )

        listOf(
            "not-json",
            "{}",
            """{"topic":"street/motion"}""",
            """{"topic":"street/motion","payload":null}""",
            """{"topic":"street/motion","payload":[]}""",
            """{"topic":"events","payload":{"after":[]}}""",
            """{"topic":"unknown","payload":{}}""",
        ).forEach { envelope ->
            assertEquals(previous, CameraActivityReducer.reduce(previous, envelope), envelope)
        }
    }

    @Test
    fun parsesExistingSnapshotAndEventMessageForms() {
        val snapshot = CameraActivityFixtures.stringPayloadEnvelope(
            "camera_activity",
            """{"street":{"motion":true,"objects":[{"label":"person","stationary":false}],"config":{"record":true,"detect":true}}}""",
        )
        val fromSnapshot = CameraActivityReducer.reduce(CameraActivityState(), snapshot)
        assertTrue(fromSnapshot.cameras.getValue("street").activeMotion)
        assertTrue(fromSnapshot.cameras.getValue("street").activeTracking)

        val event = CameraActivityFixtures.stringPayloadEnvelope(
            "events",
            """{"type":"start","after":{"camera":"yard","id":"car-1","label":"car","stationary":false}}""",
        )
        val fromEvent = CameraActivityReducer.reduce(fromSnapshot, event)
        assertEquals(setOf("car"), fromEvent.cameras.getValue("yard").trackedObjects)
        assertEquals(fromSnapshot.cameras["street"], fromEvent.cameras["street"])

        val objectPayload = CameraActivityReducer.reduce(
            CameraActivityState(),
            """{"topic":"camera_activity","payload":{"porch":{"motion":true,"objects":[]}}}""",
        )
        assertTrue(objectPayload.cameras.getValue("porch").activeMotion)
    }

    @Test
    fun endingPersonPreservesCar() {
        val initial = snapshot("street", """"motion":false,"objects":[]""")
        val withPerson = reduceEvent(initial, "start", "p1", "person")
        val withCar = reduceEvent(withPerson, "start", "c1", "car")
        val afterPersonEnds = reduceEvent(withCar, "end", "p1", "person")
        val camera = afterPersonEnds.cameras.getValue("street")

        assertEquals(setOf("car"), camera.trackedObjects)
        assertTrue(camera.activeTracking)
    }

    @Test
    fun endingOneOfTwoPersonsKeepsPersonUntilBothEnd() {
        val initial = snapshot("street", """"motion":false,"objects":[]""")
        val withFirst = reduceEvent(initial, "start", "p1", "person")
        val withBoth = reduceEvent(withFirst, "start", "p2", "person")
        val afterFirstEnds = reduceEvent(withBoth, "end", "p1", "person")

        assertEquals(setOf("person"), afterFirstEnds.cameras.getValue("street").trackedObjects)
        assertTrue(afterFirstEnds.cameras.getValue("street").activeTracking)
        assertFalse(reduceEvent(afterFirstEnds, "end", "p2", "person").cameras.getValue("street").activeTracking)
    }

    @Test
    fun stationaryUpdateDoesNotClearOtherObjectsOrMotion() {
        val withMotion = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.envelope("street/motion", "\"ON\""),
        )
        val withPerson = reduceEvent(withMotion, "start", "p1", "person")
        val withCar = reduceEvent(withPerson, "start", "c1", "car")
        val afterStationaryUpdate = reduceEvent(withCar, "update", "p1", label = null, stationary = true)
        val camera = afterStationaryUpdate.cameras.getValue("street")

        assertEquals(setOf("car"), camera.trackedObjects)
        assertTrue(camera.activeTracking)
        assertTrue(camera.activeMotion)
    }

    @Test
    fun updatesOneFeatureChannelWithoutChangingOthers() {
        val initial = snapshot("street", """"motion":true,"objects":[{"id":"p1","label":"person","stationary":false}],"config":{"record":true,"detect":true}""")
        val recordingDisabled = CameraActivityReducer.reduce(
            initial,
            CameraActivityFixtures.envelope("street/recordings/state", "\"OFF\""),
        )
        val detectionDisabled = CameraActivityReducer.reduce(
            recordingDisabled,
            CameraActivityFixtures.envelope("street/detect/state", "false"),
        )
        val camera = detectionDisabled.cameras.getValue("street")

        assertFalse(camera.recordingEnabled == true)
        assertFalse(camera.detectionEnabled == true)
        assertTrue(camera.activeMotion)
        assertEquals(setOf("person"), camera.trackedObjects)
    }

    @Test
    fun partialCameraActivitySnapshotPreservesOtherCameras() {
        val initial = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "camera_activity",
                """{"street":{"motion":true,"objects":[{"id":"p1","label":"person","stationary":false}]},"yard":{"motion":true,"objects":[{"id":"c1","label":"car","stationary":false}]}}""",
            ),
        )

        val updated = CameraActivityReducer.reduce(
            initial,
            CameraActivityFixtures.stringPayloadEnvelope(
                "camera_activity",
                """{"street":{"motion":false,"objects":[]}}""",
            ),
        )

        assertFalse(updated.cameras.getValue("street").activeRecording)
        assertTrue(updated.cameras.getValue("yard").activeRecording)
        assertEquals(setOf("car"), updated.cameras.getValue("yard").trackedObjects)
    }

    @Test
    fun partialSnapshotMergesCamerasAndEmptySnapshotPreservesState() {
        val initial = snapshot("street", """"motion":true,"objects":[{"id":"p1","label":"person","stationary":false}]""")
        val merged = snapshot("yard", """"motion":false,"objects":[{"id":"c1","label":"car","stationary":false}]""", initial)

        assertEquals(setOf("street", "yard"), merged.cameras.keys)
        assertEquals(setOf("person"), merged.cameras.getValue("street").trackedObjects)
        assertEquals(setOf("car"), merged.cameras.getValue("yard").trackedObjects)

        val unchanged = CameraActivityReducer.reduce(
            merged,
            CameraActivityFixtures.stringPayloadEnvelope("camera_activity", "{}"),
        )
        assertEquals(merged, unchanged)
        assertEquals(unchanged, CameraActivityReducer.reduce(unchanged, CameraActivityFixtures.stringPayloadEnvelope("camera_activity", "invalid")))
    }

    @Test
    fun reductionDoesNotMutatePreviousState() {
        val previous = snapshot(
            "street",
            """"motion":false,"objects":[{"id":"p1","label":"person","stationary":false},{"id":"c1","label":"car","stationary":false}]""",
        )
        val previousCamera = previous.cameras.getValue("street")
        val previousObjects = previous.runtimeByCamera.getValue("street").objectsById

        val updated = reduceEvent(previous, "end", "p1", "person")

        assertEquals(setOf("person", "car"), previousCamera.trackedObjects)
        assertEquals(setOf("p1", "c1"), previousObjects.keys)
        assertEquals(setOf("car"), updated.cameras.getValue("street").trackedObjects)
    }

    @Test
    fun updateWithoutLabelKeepsKnownLabelAndUnknownEndDoesNotRemoveObjects() {
        val withPerson = reduceEvent(CameraActivityState(), "start", "p1", "person")
        val updated = reduceEvent(withPerson, "update", "p1", label = null)
        val afterUnknownEnd = reduceEvent(updated, "end", "missing", "object")

        assertEquals(setOf("person"), afterUnknownEnd.cameras.getValue("street").trackedObjects)
        assertEquals(afterUnknownEnd, reduceEvent(afterUnknownEnd, "start", "p1", "person"))
    }

    @Test
    fun eventsWithoutCameraOrObjectIdAreIgnored() {
        val withPerson = reduceEvent(CameraActivityState(), "start", "p1", "person")
        val missingId = CameraActivityFixtures.stringPayloadEnvelope(
            "events",
            """{"type":"end","after":{"camera":"street","label":"person"}}""",
        )
        val missingCamera = CameraActivityFixtures.stringPayloadEnvelope(
            "events",
            """{"type":"end","after":{"id":"p1","label":"person"}}""",
        )

        assertEquals(withPerson, CameraActivityReducer.reduce(withPerson, missingId))
        assertEquals(withPerson, CameraActivityReducer.reduce(withPerson, missingCamera))
    }

    @Test
    fun newReviewEventImmediatelyMarksCameraActiveAndTracksItsObject() {
        val state = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "events",
                """{"type":"new","after":{"camera":"street","id":"person-1","label":"person","stationary":false}}""",
            ),
        )

        assertTrue(state.cameras.getValue("street").activeTracking)
        assertEquals(setOf("person"), state.cameras.getValue("street").trackedObjects)
    }

    @Test
    fun newObjectWithoutLabelUsesFallbackLabelAndKeepsItsIdentity() {
        val state = CameraActivityReducer.reduce(
            CameraActivityState(),
            CameraActivityFixtures.stringPayloadEnvelope(
                "events",
                """{"type":"start","after":{"camera":"street","id":"unknown-label","stationary":false}}""",
            ),
        )
        val runtime = state.runtimeByCamera.getValue("street")

        assertEquals("object", runtime.objectsById.getValue("unknown-label").label)
        assertEquals(setOf("object"), state.cameras.getValue("street").trackedObjects)
    }

    @Test
    fun enabledTopicSuppressesActivityAndReenableRestoresIt() {
        val initial = snapshot("street", """"motion":true,"objects":[{"id":"p1","label":"person"}],"config":{"enabled":true}""")
        val disabled = CameraActivityReducer.reduce(initial, CameraActivityFixtures.envelope("street/enabled/state", "\"OFF\""))
        assertFalse(disabled.cameras.getValue("street").activeMotion)
        assertFalse(disabled.cameras.getValue("street").activeTracking)
        val enabled = CameraActivityReducer.reduce(disabled, CameraActivityFixtures.envelope("street/enabled/state", "\"ON\""))
        assertTrue(enabled.cameras.getValue("street").activeMotion)
    }

    @Test
    fun statsOfflineRequiresKnownZeroFpsAndUptimeOverSixty() {
        fun stats(previous: CameraActivityState, body: String) = CameraActivityReducer.reduce(
            previous, CameraActivityFixtures.stringPayloadEnvelope("stats", body),
        )
        val early = stats(CameraActivityState(), """{"service":{"uptime":60},"cameras":{"street":{"camera_fps":0}}}""")
        assertFalse(early.cameras.getValue("street").offline == true)
        val offline = stats(early, """{"service":{"uptime":61},"cameras":{"street":{"camera_fps":0}}}""")
        assertEquals(true, offline.cameras.getValue("street").offline)
        assertEquals(offline, stats(offline, """{"service":{"uptime":100},"cameras":{"street":{"camera_fps":"bad"}}}"""))
        val online = stats(offline, """{"service":{"uptime":100},"cameras":{"street":{"camera_fps":5}}}""")
        assertEquals(false, online.cameras.getValue("street").offline)
        val snapshot = snapshot("street", """"motion":true,"objects":[]""", offline)
        assertEquals(true, snapshot.cameras.getValue("street").offline)
    }

    private fun snapshot(
        camera: String,
        cameraState: String,
        previous: CameraActivityState = CameraActivityState(),
    ): CameraActivityState = CameraActivityReducer.reduce(
        previous,
        CameraActivityFixtures.stringPayloadEnvelope("camera_activity", """{"$camera":{$cameraState}}"""),
    )

    private fun reduceEvent(
        previous: CameraActivityState,
        type: String,
        id: String,
        label: String?,
        stationary: Boolean = false,
    ): CameraActivityState {
        val labelJson = label?.let { "\"label\":\"$it\"," }.orEmpty()
        val envelope = CameraActivityFixtures.stringPayloadEnvelope(
            "events",
            """{"type":"$type","after":{"camera":"street","id":"$id",$labelJson"stationary":$stationary}}""",
        )
        return CameraActivityReducer.reduce(previous, envelope)
    }
}
