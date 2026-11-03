package com.frigate.android.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun rememberPictureInPictureMode(activity: ComponentActivity?): Boolean {
    var active by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { active = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return active
}

internal fun enterVideoPictureInPicture(activity: Activity, aspect: Float, bounds: Rect): Boolean {
    val ratio = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(.42f, 2.39f) else 16f / 9
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((ratio * 10_000).toInt(), 10_000))
        .setSeamlessResizeEnabled(true)
        .apply { if (!bounds.isEmpty) setSourceRectHint(bounds) }
        .build()
    return try {
        activity.enterPictureInPictureMode(params)
    } catch (_: IllegalStateException) {
        false
    }
}

@Composable
internal fun ImmersivePlayerWindow(activity: Activity?, enabled: Boolean) {
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        if (enabled) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
    }
}
