package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraActivityDispatchTest {
    @Test
    fun reviewMessagesReachTheReviewCallbackEvenWhenCameraStateIsUnchanged() {
        var reviews = 0
        var activities = 0

        val state = CameraActivityDispatch.dispatch(
            raw = """{"topic":"reviews","payload":"{\"type\":\"end\"}"}""",
            state = CameraActivityState(),
            onReviewsMessage = { reviews++ },
            onActivityChanged = { activities++ },
        )

        assertEquals(1, reviews)
        assertEquals(0, activities)
        assertTrue(state.cameras.isEmpty())
    }

    @Test
    fun cameraActivityMessagesStillUpdateTheState() {
        var activities = 0

        val state = CameraActivityDispatch.dispatch(
            raw = """{"topic":"camera_activity","payload":"{\"front_door\":{\"motion\":true}}"}""",
            state = CameraActivityState(),
            onActivityChanged = { activities++ },
        )

        assertEquals(1, activities)
        assertTrue(state.cameras["front_door"]!!.activeMotion)
    }

    @Test
    fun otherTopicsDoNotReachTheReviewCallback() {
        var reviews = 0

        CameraActivityDispatch.dispatch(
            raw = """{"topic":"stats","payload":"{}"}""",
            state = CameraActivityState(),
            onReviewsMessage = { reviews++ },
        )

        assertEquals(0, reviews)
    }
}
