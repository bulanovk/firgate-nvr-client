package com.frigate.android.feature.history

/** The touch actions of the archive surface, without the Android types, so that the rule stays testable. */
internal enum class FrameTouchAction {
    Down,
    Move,
    Up,
    Cancel,
    PointerDown,
    PointerUp,
}

/**
 * Decides whether a touch on the frame is a tap.
 *
 * A tap is one finger that is released without moving beyond the touch slop. The second tap of a
 * double tap does not count: a double tap resets the zoom and must not throw the controls twice.
 */
internal class HistoryFrameTap(
    private val touchSlopPx: Float,
    private val doubleTapTimeoutMs: Long = 300L,
) {
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var multiTouch = false
    private var lastTapAtMs: Long? = null

    /** Returns true when the action ends a tap that has to be reported. */
    fun onAction(action: FrameTouchAction, x: Float, y: Float, eventTimeMs: Long): Boolean {
        when (action) {
            FrameTouchAction.Down -> {
                downX = x
                downY = y
                moved = false
                multiTouch = false
            }

            FrameTouchAction.PointerDown -> multiTouch = true

            FrameTouchAction.Move -> {
                val dx = x - downX
                val dy = y - downY
                if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) moved = true
            }

            FrameTouchAction.Cancel -> {
                moved = true
                multiTouch = true
            }

            FrameTouchAction.PointerUp -> Unit

            FrameTouchAction.Up -> {
                val isTap = !moved && !multiTouch
                val secondOfDoubleTap = lastTapAtMs?.let { eventTimeMs - it <= doubleTapTimeoutMs } ?: false
                lastTapAtMs = eventTimeMs
                return isTap && !secondOfDoubleTap
            }
        }
        return false
    }
}
