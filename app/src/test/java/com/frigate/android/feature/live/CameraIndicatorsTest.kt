package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateCamera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CameraIndicatorsTest {
    @Test
    fun exposesRecordingAndDetectedObjectIndicators() {
        val camera = FrigateCamera(
            id = "front_door",
            name = "Front door",
            enabled = true,
            recordEnabled = true,
            detectEnabled = true,
        )

        assertEquals(
            listOf(
                CameraIndicator.Recording,
                CameraIndicator.Object("car"),
                CameraIndicator.Object("person"),
            ),
            cameraIndicators(camera, CameraActivity(trackedObjects = setOf("person", "car"))),
        )
    }

    @Test
    fun usesRuntimeRecordingAndDetectionStatesOverConfigurationDefaults() {
        val camera = FrigateCamera(
            id = "front_door",
            name = "Front door",
            enabled = true,
            recordEnabled = true,
            detectEnabled = true,
        )

        assertEquals(
            emptyList<CameraIndicator>(),
            cameraIndicators(
                camera,
                CameraActivity(
                    recordingEnabled = false,
                    detectionEnabled = false,
                    trackedObjects = setOf("person"),
                ),
            ),
        )
    }

    @Test
    fun omitsDisabledRecordingAndDetectionIndicators() {
        val camera = FrigateCamera(
            id = "front_door",
            name = "Front door",
            enabled = true,
            recordEnabled = false,
            detectEnabled = false,
        )

        assertEquals(
            emptyList<CameraIndicator>(),
            cameraIndicators(camera, CameraActivity(trackedObjects = setOf("person"))),
        )
    }
}
