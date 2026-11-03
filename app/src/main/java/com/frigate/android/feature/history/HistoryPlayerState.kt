package com.frigate.android.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The playback state of the archive player, mirroring the PWA controls. */
internal data class HistoryPlayerState(
    val positionSeconds: Double = 0.0,
    val playing: Boolean = false,
    val muted: Boolean = true,
    val speed: Float = 1.0f,
    val controlsVisible: Boolean = true,
    val loading: Boolean = false,
    val error: HistoryError? = null,
)

/** The failures the archive player can show; every one of them offers a retry. */
internal sealed class HistoryError {
    data object NoRecording : HistoryError()
    data object Forbidden : HistoryError()
    data object Unauthorized : HistoryError()
    data class Network(val message: String) : HistoryError()
    data class Codec(val message: String) : HistoryError()
}

internal enum class HistoryFailureKind { HTTP, NETWORK, CODEC }

internal fun classifyHistoryError(kind: HistoryFailureKind, statusCode: Int?, message: String): HistoryError =
    when (kind) {
        HistoryFailureKind.HTTP -> when (statusCode) {
            404 -> HistoryError.NoRecording
            403 -> HistoryError.Forbidden
            401 -> HistoryError.Unauthorized
            else -> HistoryError.Network("Frigate returned HTTP $statusCode.")
        }

        HistoryFailureKind.NETWORK -> HistoryError.Network(message)
        HistoryFailureKind.CODEC -> HistoryError.Codec(message)
    }

internal val HISTORY_SPEEDS = listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f)

/**
 * The label of the rate control.
 *
 * The PWA writes the rate as it is stored in its list: `0.5x`, `1x`, `2x`, `16x`.
 */
internal fun historyRateLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

internal const val HISTORY_CONTROLS_TIMEOUT_MILLIS = 4_000L

/**
 * The playback state of the archive player.
 *
 * The controller owns the pure transitions only: the player, the timeline and the panels feed it
 * with positions, failures and user input and read the resulting state back.
 */
internal class HistoryPlayerController(
    initial: HistoryPlayerState = HistoryPlayerState(),
    bounds: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // The playhead moves four times a second, every other field changes on a tap. They live in two
    // snapshot states, so a step of the playhead recomposes only what reads the position.
    private var core: HistoryPlayerState by mutableStateOf(initial.copy(positionSeconds = 0.0))
    private var position: Double by mutableDoubleStateOf(initial.positionSeconds)

    /** The whole state; reading it observes the position too. */
    var state: HistoryPlayerState
        get() = core.copy(positionSeconds = position)
        private set(value) {
            if (value.positionSeconds != position) position = value.positionSeconds
            core = value.copy(positionSeconds = 0.0)
        }

    /** The state without the playhead (its `positionSeconds` is always 0): a step of the playhead does not invalidate it. */
    val playback: HistoryPlayerState get() = core

    /** The playhead alone. */
    val positionSeconds: Double get() = position

    var bounds: ClosedFloatingPointRange<Double> = bounds
        set(value) {
            field = value
            state = state.copy(positionSeconds = state.positionSeconds.coerceIn(value.start, value.endInclusive))
        }

    /** Bumped on every retry so the player rebuilds its source. */
    var retryToken: Int by mutableStateOf(0)
        private set

    private var controlsShownAtMillis: Long = clock()

    init {
        state = state.copy(positionSeconds = initial.positionSeconds.coerceIn(bounds.start, bounds.endInclusive))
    }

    fun togglePlay() {
        state = state.copy(playing = !state.playing)
        controlsShownAtMillis = clock()
    }

    fun play() {
        if (!state.playing) togglePlay()
    }

    fun pause() {
        if (state.playing) togglePlay()
    }

    fun seekBy(deltaSeconds: Double) {
        seekTo(state.positionSeconds + deltaSeconds)
    }

    fun seekTo(seconds: Double) {
        state = state.copy(positionSeconds = seconds.coerceIn(bounds.start, bounds.endInclusive))
        controlsShownAtMillis = clock()
    }

    fun onPositionChanged(seconds: Double) {
        state = state.copy(positionSeconds = seconds.coerceIn(bounds.start, bounds.endInclusive))
    }

    fun onEnded() {
        state = state.copy(playing = false)
    }

    // Every interaction with the controls keeps them visible for the full timeout window: the bar
    // would otherwise vanish right under the finger that pressed the button.
    fun toggleMute() {
        state = state.copy(muted = !state.muted)
        revealControls()
    }

    fun setSpeed(speed: Float) {
        if (speed !in HISTORY_SPEEDS) return
        state = state.copy(speed = speed)
        revealControls()
    }

    /** A tap on the frame only reveals the controls, it never changes the playback. */
    fun onFrameTapped() {
        revealControls()
    }

    fun revealControls() {
        state = state.copy(controlsVisible = true)
        controlsShownAtMillis = clock()
    }

    fun onControlsExpired() {
        if (!state.controlsVisible) return
        if (clock() - controlsShownAtMillis < HISTORY_CONTROLS_TIMEOUT_MILLIS) return
        state = state.copy(controlsVisible = false)
    }

    fun onLoading(loading: Boolean) {
        state = state.copy(loading = loading)
    }

    fun onError(error: HistoryError?) {
        state = state.copy(error = error)
    }

    fun retry() {
        state = state.copy(error = null, loading = true)
        retryToken += 1
    }
}
