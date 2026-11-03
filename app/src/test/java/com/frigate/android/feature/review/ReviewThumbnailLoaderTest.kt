package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewThumbnailLoaderTest {
    private fun review(path: String?) = FrigateReview(
        id = "1",
        camera = "front_door",
        startTime = 1_700_000_000.0,
        endTime = 1_700_000_060.0,
        severity = "alert",
        hasBeenReviewed = false,
        thumbnailPath = path,
        objectLabels = listOf("person"),
    )

    private fun webpBytes(): ByteArray =
        "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)

    @Test
    fun loadsThumbnailFromTheProfileBaseWithTheSessionCookie() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/webp").setBody(Buffer().write(webpBytes())))
            val profile = ServerProfile("test", server.url("/frigate").toString().trimEnd('/'))

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/review/thumb-front_door-1.webp"),
            )

            val request = server.takeRequest()
            assertEquals("/frigate/clips/review/thumb-front_door-1.webp", request.path)
            assertEquals("session=secret", request.getHeader("Cookie"))
            assertArrayEquals(webpBytes(), bytes)
        }
    }

    @Test
    fun returnsNullWithoutARequestForAnUnknownThumbnailPath() = runBlocking {
        MockWebServer().use { server ->
            val profile = ServerProfile("test", server.url("/").toString())

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/export/thumb-front_door-1.webp"),
            )

            assertNull(bytes)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun rejectsRedirectsInsteadOfFollowingThem() = runBlocking {
        MockWebServer().use { server ->
            val other = MockWebServer()
            other.start()
            try {
                server.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", other.url("/thumb.webp").toString()),
                )
                val profile = ServerProfile("test", server.url("/").toString())

                val bytes = ReviewThumbnailLoader.load(
                    profile,
                    "session=secret",
                    review("/media/frigate/clips/review/thumb-front_door-1.webp"),
                )

                assertNull(bytes)
                assertEquals(0, other.requestCount)
            } finally {
                other.shutdown()
            }
        }
    }

    @Test
    fun rejectsResponsesThatAreNotImages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>nope</html>"))
            val profile = ServerProfile("test", server.url("/").toString())

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/review/thumb-front_door-1.webp"),
            )

            assertNull(bytes)
        }
    }

    @Test
    fun rejectsPayloadsThatAreNotImagesEvenWithoutAContentType() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not an image"))
            val profile = ServerProfile("test", server.url("/").toString())

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/review/thumb-front_door-1.webp"),
            )

            assertNull(bytes)
        }
    }

    @Test
    fun rejectsOversizedPayloads() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(Buffer().write(ByteArray(ReviewThumbnailLoader.MAX_BYTES + 1024))),
            )
            val profile = ServerProfile("test", server.url("/").toString())

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/review/thumb-front_door-1.webp"),
            )

            assertNull(bytes)
        }
    }

    @Test
    fun rejectsFailedResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val profile = ServerProfile("test", server.url("/").toString())

            val bytes = ReviewThumbnailLoader.load(
                profile,
                "session=secret",
                review("/media/frigate/clips/review/thumb-front_door-1.webp"),
            )

            assertNull(bytes)
        }
    }

    @Test
    fun cancelsTheHttpCallWhenTheCoroutineIsCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val profile = ServerProfile("test", server.url("/").toString())
            val events = mutableListOf<String>()
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        synchronized(events) { events += "canceled" }
                    }
                })
                .build()

            val job = launch(Dispatchers.Default) {
                ReviewThumbnailLoader.load(
                    profile,
                    "session=secret",
                    review("/media/frigate/clips/review/thumb-front_door-1.webp"),
                    client,
                )
            }
            server.takeRequest(5, TimeUnit.SECONDS)
            delay(100)
            job.cancel()
            job.join()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()

            assertTrue(job.isCancelled)
            assertTrue(synchronized(events) { events.toList() }.contains("canceled"))
        }
    }

    @Test
    fun recognisesSupportedImagePayloads() {
        assertTrue(isSupportedImagePayload(webpBytes()))
        assertTrue(isSupportedImagePayload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)))
        assertTrue(
            isSupportedImagePayload(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        assertTrue(isSupportedImagePayload("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertEquals(false, isSupportedImagePayload("<html>".toByteArray()))
        assertEquals(false, isSupportedImagePayload(ByteArray(0)))
    }

    @Test
    fun failsWithAnExceptionWhenTheTransportBreaks() = runBlocking {
        MockWebServer().use { server ->
            server.shutdown()
            val profile = ServerProfile("test", server.url("/").toString())

            val result = runCatching {
                ReviewThumbnailLoader.load(
                    profile,
                    "session=secret",
                    review("/media/frigate/clips/review/thumb-front_door-1.webp"),
                )
            }

            assertTrue(result.exceptionOrNull() is IOException)
        }
    }
}
