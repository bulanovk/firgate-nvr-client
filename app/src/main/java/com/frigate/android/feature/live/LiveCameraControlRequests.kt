package com.frigate.android.feature.live

import androidx.compose.runtime.mutableStateMapOf

/**
 * The requests of the camera controls that wait for the camera to confirm them.
 *
 * Every request carries a token: the timeout of an old request must not cancel a newer request
 * of the same control, and the request of one control must not touch the request of another.
 */
internal class LiveCameraControlRequests {
    private data class Request(val expected: Boolean, val token: Long)

    private val requests = mutableStateMapOf<CameraControl, Request>()
    private var counter = 0L

    /** Starts a request and returns the token that belongs to it. */
    fun begin(control: CameraControl, expected: Boolean): Long {
        val token = ++counter
        requests[control] = Request(expected, token)
        return token
    }

    /** Removes the request when the token still belongs to it. */
    fun finish(control: CameraControl, token: Long): Boolean {
        val request = requests[control] ?: return false
        if (request.token != token) return false
        requests.remove(control)
        return true
    }

    /** Removes the request when the camera confirms the value the user asked for. */
    fun confirm(control: CameraControl, expected: Boolean): Boolean {
        val request = requests[control] ?: return false
        if (request.expected != expected) return false
        requests.remove(control)
        return true
    }

    fun isPending(control: CameraControl): Boolean = control in requests

    /** The value the user asked for, or null when there is no request. */
    fun expectedValue(control: CameraControl): Boolean? = requests[control]?.expected

    fun pendingControls(): Set<CameraControl> = requests.keys.toSet()
}
