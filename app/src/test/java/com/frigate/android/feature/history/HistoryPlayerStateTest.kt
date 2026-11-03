package com.frigate.android.feature.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryPlayerStateTest {
    private var now = 0L
    private fun controller(bounds: ClosedFloatingPointRange<Double> = 100.0..200.0) =
        HistoryPlayerController(
            initial = HistoryPlayerState(positionSeconds = 150.0),
            bounds = bounds,
            clock = { now },
        )

    @Test
    fun startsPausedMutedAndWithoutAnError() {
        val controller = controller()

        assertFalse(controller.state.playing)
        assertTrue(controller.state.muted)
        assertEquals(1.0f, controller.state.speed)
        assertTrue(controller.state.controlsVisible)
        assertFalse(controller.state.loading)
        assertNull(controller.state.error)
    }

    @Test
    fun playAndPauseToggleThePlayback() {
        val controller = controller()

        controller.togglePlay()
        assertTrue(controller.state.playing)

        controller.togglePlay()
        assertFalse(controller.state.playing)
    }

    @Test
    fun seekingByTenSecondsClampsToTheRange() {
        val controller = controller()

        controller.seekBy(-10.0)
        assertEquals(140.0, controller.state.positionSeconds)

        controller.seekBy(-1000.0)
        assertEquals(100.0, controller.state.positionSeconds)

        controller.seekBy(10_000.0)
        assertEquals(200.0, controller.state.positionSeconds)
    }

    @Test
    fun seekingByOneSecondStaysInsideTheRange() {
        val controller = controller()

        controller.seekBy(-1.0)
        assertEquals(149.0, controller.state.positionSeconds)

        controller.seekBy(-1000.0)
        assertEquals(100.0, controller.state.positionSeconds)
    }

    @Test
    fun seekingToAnAbsoluteTimeClampsToTheRange() {
        val controller = controller()

        controller.seekTo(175.0)
        assertEquals(175.0, controller.state.positionSeconds)

        controller.seekTo(9_999.0)
        assertEquals(200.0, controller.state.positionSeconds)
    }

    @Test
    fun thePositionOfThePlayerIsClampedToTheRange() {
        val controller = controller()

        controller.onPositionChanged(1_000.0)
        assertEquals(200.0, controller.state.positionSeconds)

        controller.onPositionChanged(-10.0)
        assertEquals(100.0, controller.state.positionSeconds)
    }

    @Test
    fun muteTogglesTheSound() {
        val controller = controller()

        controller.toggleMute()
        assertFalse(controller.state.muted)

        controller.toggleMute()
        assertTrue(controller.state.muted)
    }

    @Test
    fun theSpeedIsRestrictedToTheSupportedSteps() {
        val controller = controller()

        controller.setSpeed(3.0f)
        assertEquals(1.0f, controller.state.speed)

        controller.setSpeed(2.0f)
        assertEquals(2.0f, controller.state.speed)

        controller.setSpeed(0.5f)
        assertEquals(0.5f, controller.state.speed)
    }

    @Test
    fun theRateLabelIsFormattedLikeThePwa() {
        assertEquals("0.5x", historyRateLabel(0.5f))
        assertEquals("1x", historyRateLabel(1.0f))
        assertEquals("2x", historyRateLabel(2.0f))
        assertEquals("16x", historyRateLabel(16.0f))
    }

    @Test
    fun choosingARateKeepsTheControlsVisible() {
        val controller = controller()
        controller.togglePlay()
        now = HISTORY_CONTROLS_TIMEOUT_MILLIS
        controller.onControlsExpired()
        assertFalse(controller.state.controlsVisible)

        controller.setSpeed(16.0f)

        assertEquals(16.0f, controller.state.speed)
        assertTrue(controller.state.controlsVisible)
    }

    @Test
    fun theControlsSurviveTheTimeoutAfterTheRateIsChosen() {
        val controller = controller()
        controller.togglePlay()
        now = HISTORY_CONTROLS_TIMEOUT_MILLIS
        controller.onControlsExpired()
        assertFalse(controller.state.controlsVisible)

        controller.setSpeed(16.0f)
        now += HISTORY_CONTROLS_TIMEOUT_MILLIS - 1
        controller.onControlsExpired()

        assertTrue(controller.state.controlsVisible)
    }

    @Test
    fun mutingKeepsTheControlsVisible() {
        val controller = controller()
        controller.togglePlay()
        now = HISTORY_CONTROLS_TIMEOUT_MILLIS
        controller.onControlsExpired()
        assertFalse(controller.state.controlsVisible)

        controller.toggleMute()

        assertFalse(controller.state.muted)
        assertTrue(controller.state.controlsVisible)
    }

    @Test
    fun theControlsHideAfterFourSeconds() {
        val controller = controller()
        controller.togglePlay()

        now = 3_999L
        controller.onControlsExpired()
        assertTrue(controller.state.controlsVisible)

        now = 4_000L
        controller.onControlsExpired()
        assertFalse(controller.state.controlsVisible)
    }

    @Test
    fun tappingTheFrameRevealsTheControlsWithoutChangingThePlayback() {
        val controller = controller()
        controller.togglePlay()
        now = 10_000L
        controller.onControlsExpired()
        assertFalse(controller.state.controlsVisible)

        controller.onFrameTapped()

        assertTrue(controller.state.controlsVisible)
        assertTrue(controller.state.playing)
    }

    @Test
    fun theFailuresAreDistinguishedByKind() {
        assertEquals(HistoryError.NoRecording, classifyHistoryError(HistoryFailureKind.HTTP, 404, "missing"))
        assertEquals(HistoryError.Forbidden, classifyHistoryError(HistoryFailureKind.HTTP, 403, "denied"))
        assertEquals(HistoryError.Unauthorized, classifyHistoryError(HistoryFailureKind.HTTP, 401, "expired"))
        assertEquals(
            HistoryError.Network("Frigate returned HTTP 500."),
            classifyHistoryError(HistoryFailureKind.HTTP, 500, "boom"),
        )
        assertEquals(
            HistoryError.Network("No connection."),
            classifyHistoryError(HistoryFailureKind.NETWORK, null, "No connection."),
        )
        assertEquals(
            HistoryError.Codec("Unsupported stream."),
            classifyHistoryError(HistoryFailureKind.CODEC, null, "Unsupported stream."),
        )
    }

    @Test
    fun everyFailureCanBeRetried() {
        val controller = controller()
        controller.onError(HistoryError.NoRecording)
        assertEquals(HistoryError.NoRecording, controller.state.error)

        val token = controller.retryToken
        controller.retry()

        assertNull(controller.state.error)
        assertTrue(controller.state.loading)
        assertEquals(token + 1, controller.retryToken)
    }

    @Test
    fun theLoadingStateFollowsThePlayer() {
        val controller = controller()

        controller.onLoading(true)
        assertTrue(controller.state.loading)

        controller.onLoading(false)
        assertFalse(controller.state.loading)
    }
}
