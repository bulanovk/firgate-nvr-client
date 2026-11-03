package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiveStreamEndpointTest {
    @Test
    fun buildsMseUrlForHttpServerWithPlainWebSocket() {
        val server = ServerProfile("Home", "http://frigate.local/frigate")

        assertEquals(
            "ws://frigate.local/frigate/live/mse/api/ws?src=front_door",
            LiveStreamEndpoint.mseWebSocketUrl(server, "front_door"),
        )
    }

    @Test
    fun buildsMseUrlForServerWithoutBasePath() {
        val server = ServerProfile("Remote", "https://frigate.example.com")

        assertEquals(
            "wss://frigate.example.com/live/mse/api/ws?src=front%20door",
            LiveStreamEndpoint.mseWebSocketUrl(server, "front door"),
        )
    }

    @Test
    fun buildsMseUrlForTheConfiguredStream() {
        val server = ServerProfile("Remote", "https://frigate.example.com/frigate")

        assertEquals(
            "wss://frigate.example.com/frigate/live/mse/api/ws?src=front%20door",
            LiveStreamEndpoint.mseWebSocketUrl(server, "front door"),
        )
    }

    @Test
    fun preservesServerPortAndBasePathWhileEscapingStreamName() {
        val server = ServerProfile("Remote", "https://frigate.example.com:8971/base/path")

        assertEquals(
            "wss://frigate.example.com:8971/base/path/live/mse/api/ws?src=front%2Fdoor%20%2B%20main",
            LiveStreamEndpoint.mseWebSocketUrl(server, "front/door + main"),
        )
    }

    @Test
    fun buildsAuthenticatedFrigateWebSocketUrl() {
        val server = ServerProfile("Home", "http://frigate.local/frigate")

        assertEquals(
            "ws://frigate.local/frigate/ws",
            LiveStreamEndpoint.frigateWebSocketUrl(server),
        )
    }
}
