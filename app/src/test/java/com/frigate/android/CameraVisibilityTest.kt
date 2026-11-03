package com.frigate.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CameraVisibilityTest {
    @Test
    fun includesCameraWhenFullyInsideViewport() {
        val cameraBounds = mapOf(
            "street" to CameraBounds(top = 100f, bottom = 300f),
        )

        assertEquals(
            setOf("street"),
            visibleCameraIds(
                viewportTop = 0f,
                viewportBottom = 500f,
                cameraBounds = cameraBounds,
            ),
        )
    }

    @Test
    fun includesCamerasAtOrAboveHalfVisibleAndExcludesBelowHalf() {
        val cameraBounds = mapOf(
            "below" to CameraBounds(top = 50.1f, bottom = 150.1f),
            "equal" to CameraBounds(top = 50f, bottom = 150f),
            "above" to CameraBounds(top = 49.9f, bottom = 149.9f),
        )

        assertEquals(
            setOf("equal", "above"),
            visibleCameraIds(viewportTop = 0f, viewportBottom = 100f, cameraBounds = cameraBounds),
        )
    }

    @Test
    fun excludesItemsThatAreNotCameras() {
        val cameraBounds = mapOf(
            "street" to CameraBounds(top = 10f, bottom = 30f),
            "review-carousel" to CameraBounds(top = 40f, bottom = 200f),
        )

        assertEquals(
            setOf("street"),
            visibleCameraIds(
                viewportTop = 0f,
                viewportBottom = 300f,
                cameraBounds = cameraBounds,
                excludedKeys = setOf("review-carousel"),
            ),
        )
    }

    @Test
    fun excludesInvalidViewportAndCameraBounds() {
        val cameraBounds = mapOf(
            "zero" to CameraBounds(top = 20f, bottom = 20f),
            "reversed" to CameraBounds(top = 30f, bottom = 10f),
            "valid" to CameraBounds(top = 10f, bottom = 30f),
        )

        assertEquals(emptySet<String>(), visibleCameraIds(0f, 0f, cameraBounds))
        assertEquals(emptySet<String>(), visibleCameraIds(10f, 0f, cameraBounds))
        assertEquals(setOf("valid"), visibleCameraIds(0f, 40f, cameraBounds))
    }
}
