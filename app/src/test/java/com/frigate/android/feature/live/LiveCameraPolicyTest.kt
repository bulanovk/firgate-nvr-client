package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

class LiveCameraPolicyTest {
    @ParameterizedTest
    @CsvSource(
        "true, true, true, DEFER",
        "true, false, true, DEFER",
        "false, true, true, DEFER",
        "false, false, true, DEFER",
        "true, true, false, START",
        "true, false, false, SNAPSHOT",
        "false, true, false, PAUSE",
        "false, false, false, PAUSE",
    )
    fun appliesPolicyForEveryVisibilityActivityAndScrollCombination(
        isVisible: Boolean,
        hasActivity: Boolean,
        isScrolling: Boolean,
        expected: String,
    ) {
        assertEquals(expected, cameraPlaybackAction(isVisible, hasActivity, isScrolling).name)
    }

    @Test
    fun reappliesLatestVisibilityAndActivityAfterScrollingStops() {
        assertEquals(CameraPlaybackAction.DEFER, cameraPlaybackAction(true, false, true))
        assertEquals(CameraPlaybackAction.DEFER, cameraPlaybackAction(false, true, true))
        assertEquals(CameraPlaybackAction.START, cameraPlaybackAction(true, true, false))
        assertEquals(CameraPlaybackAction.PAUSE, cameraPlaybackAction(false, true, false))
        assertEquals(CameraPlaybackAction.SNAPSHOT, cameraPlaybackAction(true, false, false))
    }

    @Test
    fun streamAndSnapshotPredicatesMatchPlaybackPolicy() {
        assertTrue(shouldStreamCamera(isVisible = true, hasActivity = true))
        assertFalse(shouldStreamCamera(isVisible = false, hasActivity = true))
        assertFalse(shouldStreamCamera(isVisible = true, hasActivity = false))
        assertTrue(shouldShowSnapshot(isVisible = true, hasActivity = false))
        assertFalse(shouldShowSnapshot(isVisible = false, hasActivity = false))
        assertFalse(shouldShowSnapshot(isVisible = true, hasActivity = true))
    }

    @ParameterizedTest
    @CsvSource("true, true, true", "false, true, true", "true, false, false", "false, false, false")
    fun selectsLiveFromActivityNotRecording(recordEnabled: Boolean, motion: Boolean, expected: Boolean) {
        assertEquals(expected, liveTileDemand(true, true,
            CameraActivity(activeMotion = motion, recordingEnabled = recordEnabled), "stream"))
    }

    @ParameterizedTest
    @CsvSource("true, true, true", "true, false, false", "false, true, false", "false, false, false")
    fun runsLiveResourcesOnlyWhileActivityIsStartedAndLiveScreenIsVisible(
        isActivityStarted: Boolean,
        isLiveScreen: Boolean,
        expected: Boolean,
    ) {
        assertEquals(expected, shouldRunLiveResources(isActivityStarted, isLiveScreen))
    }
}
