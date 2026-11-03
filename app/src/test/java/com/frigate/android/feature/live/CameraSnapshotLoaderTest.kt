package com.frigate.android.feature.live

import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class CameraSnapshotLoaderTest {
    @Test
    fun encodesCameraIdInLatestWebpPathAndAddsSessionCookie() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("webp-bytes"))
        server.start()
        try {
            val bytes = CameraSnapshotLoader.loadBytes(
                ServerProfile("test", server.url("/base").toString().trimEnd('/')),
                "front/door + main",
                "session=secret",
            )
            val request = server.takeRequest()
            assertEquals("/base/api/front%2Fdoor%20%2B%20main/latest.webp", request.path)
            assertEquals("session=secret", request.getHeader("Cookie"))
            assertArrayEquals("webp-bytes".toByteArray(), bytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun returnsOfflineMetadataAndBoundsRequestedHeight() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("X-Frigate-Offline", "true").setBody("image"))
            val result = CameraSnapshotLoader.load(
                ServerProfile("test", server.url("/").toString()), "yard", "cookie=test", 4000, 123,
            )
            assertEquals(true, result.offline)
            val url = server.takeRequest().requestUrl!!
            assertEquals("1080", url.queryParameter("height"))
            assertEquals("123", url.queryParameter("cache"))
        }
    }

    @Test
    fun rejectsNonSuccessResponse(): Unit = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            assertThrows(IOException::class.java) {
                runBlocking {
                    CameraSnapshotLoader.loadBytes(
                        ServerProfile("test", server.url("/").toString()), "front_door", "session=secret",
                    )
                }
            }
        } finally {
            server.shutdown()
        }
    }
}
