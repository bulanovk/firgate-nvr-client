package com.frigate.android.feature.player

import android.view.View
import androidx.media3.common.Player

/**
 * Keeps the screen on while [player] plays into [view].
 *
 * The screen stays on only while the video moves: a paused archive, a stalled stream and a grid
 * without video let it go out as usual. The flag belongs to the view, so the window drops it as
 * soon as the view leaves the screen.
 */
internal class ScreenOnWhilePlaying(
    private val view: View,
    private val player: Player,
) : Player.Listener, View.OnAttachStateChangeListener {
    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(view: View) {
        player.addListener(this)
        view.keepScreenOn = player.isPlaying
    }

    override fun onViewDetachedFromWindow(view: View) {
        player.removeListener(this)
        view.keepScreenOn = false
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        view.keepScreenOn = isPlaying
    }
}
