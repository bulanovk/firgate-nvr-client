package com.frigate.android.feature.events

import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class EventsExportTransportTest {
    @Test
    fun streamsSameOriginFileWithHeaderCookieWithoutRedirects() = runBlocking {
        MockWebServer().use { server ->
            val transport = EventsExportTransport(ServerProfile("Test",server.url("/frigate").toString()), "test=value")
            server.enqueue(MockResponse().setBody("video-data"))
            val output = ByteArrayOutputStream()
            assertEquals(10,transport.download("exports/video.mp4",output))
            assertEquals("video-data", output.toString("UTF-8"))
            val request = server.takeRequest()
            assertEquals("/frigate/exports/video.mp4",request.path)
            assertEquals("test=value",request.getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            assertThrows(IOException::class.java) { runBlocking { transport.download("exports/redirect.mp4",ByteArrayOutputStream()) } }
            assertEquals(2,server.requestCount)
        }
    }
    @Test
    fun rejectsForeignOrNonExportPathsBeforeRequestAndCapsStream() = runBlocking {
        MockWebServer().use { server ->
            val transport = EventsExportTransport(ServerProfile("Test",server.url("/").toString()), "", maxBytes = 3)
            listOf("https://foreign.example/exports/video.mp4", "exports/../private", "api/config").forEach { path ->
                assertThrows(IOException::class.java) { runBlocking { transport.download(path,ByteArrayOutputStream()) } }
            }
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("1234"))
            assertThrows(IOException::class.java) { runBlocking { transport.download("exports/video.mp4",ByteArrayOutputStream()) } }
        }
    }
}
