package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LiveWebSocketClientTest {
    @Test
    fun doesNotFollowRedirectsWithSessionCookieRequests() {
        val client = createLiveWebSocketClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
