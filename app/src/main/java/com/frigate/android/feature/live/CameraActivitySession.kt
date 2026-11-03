package com.frigate.android.feature.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.frigate.android.core.server.ServerProfile

internal class CameraActivitySession(
    val activity: Map<String, CameraActivity>,
    val setControl: (String, CameraControl, Boolean) -> Boolean,
)

@Composable
internal fun rememberCameraActivity(
    profile: ServerProfile,
    cookie: String,
    onReviewsMessage: (String) -> Unit = {},
    onReconnect: () -> Unit = {},
): CameraActivitySession {
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    var activity by remember(profile, cookie) { mutableStateOf(emptyMap<String, CameraActivity>()) }
    val currentOnReviews by rememberUpdatedState(onReviewsMessage)
    val currentOnReconnect by rememberUpdatedState(onReconnect)
    val socket = remember(profile, cookie) {
        CameraActivityWebSocket(
            server = profile,
            cookie = cookie,
            onActivityChanged = { activity = it },
            onReviewsMessage = { currentOnReviews(it) },
            onReconnect = { currentOnReconnect() },
        )
    }
    DisposableEffect(socket, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> socket.start()
                Lifecycle.Event.ON_STOP -> socket.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) socket.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            socket.stop()
        }
    }
    return CameraActivitySession(activity, socket::setControl)
}
