package com.frigate.android.core.server

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LiveStreamEndpoint {
    fun mseWebSocketUrl(server: ServerProfile, camera: String): String {
        return webSocketUrl(server, "live/mse/api/ws", camera)
    }

    fun frigateWebSocketUrl(server: ServerProfile): String {
        val base = URI(server.baseUrl)
        val scheme = if (base.scheme == "https") "wss" else "ws"
        val path = base.rawPath.trimEnd('/')
        return "$scheme://${base.rawAuthority}$path/ws"
    }

    private fun webSocketUrl(server: ServerProfile, endpoint: String, camera: String): String {
        val base = URI(server.baseUrl)
        val scheme = if (base.scheme == "https") "wss" else "ws"
        val path = base.rawPath.trimEnd('/')
        val encodedCamera = URLEncoder.encode(camera, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "$scheme://${base.rawAuthority}$path/$endpoint?src=$encodedCamera"
    }
}
