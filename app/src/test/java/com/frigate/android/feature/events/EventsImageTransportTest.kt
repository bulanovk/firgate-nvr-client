package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsImageTransportTest {
    @Test
    fun regionBackgroundRequestsBoundedHeightWithinProfileBasePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image"))
            val transport = EventsImageTransport(ServerProfile("Test", server.url("/base").toString()), "test=value")
            assertArrayEquals("image".toByteArray(), transport.load("api/yard/latest.jpg", height = 500))
            val request = server.takeRequest()
            assertEquals("/base/api/yard/latest.jpg?h=500", request.path)
            assertEquals("test=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun authenticatedImagesAreBoundedAndUnauthorizedIsNotMissing() = runBlocking {
        MockWebServer().use { server ->
            val transport = EventsImageTransport(ServerProfile("Test", server.url("/base").toString()), "test=value", maxBytes = 4)
            server.enqueue(MockResponse().setBody("abc"))
            assertArrayEquals("abc".toByteArray(), transport.load("clips/image.webp"))
            assertEquals("test=value", server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("oversized"))
            assertThrows(java.io.IOException::class.java) { runBlocking { transport.load("clips/large.webp") } }
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(401, assertThrows(FrigateHttpException::class.java) { runBlocking { transport.load("clips/expired.webp") } }.code)
            assertThrows(java.io.IOException::class.java) { runBlocking { transport.load("https://other.test/image.webp") } }
            assertEquals(3, server.requestCount)
        }
    }
}
