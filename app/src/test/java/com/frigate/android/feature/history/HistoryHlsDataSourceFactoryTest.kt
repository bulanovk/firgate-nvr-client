package com.frigate.android.feature.history

import com.frigate.android.core.server.ServerProfile
import java.net.URI
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryHlsDataSourceFactoryTest {
    private fun transport(server: MockWebServer, cookie: String = "session=secret"): HistoryMediaTransport {
        val profile = ServerProfile("Test", server.url("/frigate").toString())
        return HistoryHlsDataSourceFactory.transport(profile, cookie)
    }

    @Test
    fun acceptsTheUrlsOfTheOwnOrigin() {
        val server = MockWebServer()
        server.start()
        try {
            val transport = transport(server)
            val url = server.url("/frigate/vod/front_door/master.m3u8").toString()

            assertEquals(url, transport.checkUrl(url).toString())
            assertTrue(transport.isSameOrigin(URI(server.url("/other").toString())))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun comparesTheEffectivePortOfBothUrls() {
        val transport = HistoryMediaTransport(URI("https://frigate.example.com"), "session=secret")

        assertTrue(transport.isSameOrigin(URI("https://frigate.example.com/vod/x/master.m3u8")))
        assertTrue(transport.isSameOrigin(URI("https://frigate.example.com:443/vod/x/master.m3u8")))
        assertFalse(transport.isSameOrigin(URI("https://frigate.example.com:8443/vod/x/master.m3u8")))
        assertFalse(transport.isSameOrigin(URI("http://frigate.example.com/vod/x/master.m3u8")))
        assertFalse(transport.isSameOrigin(URI("https://other.example.com/vod/x/master.m3u8")))
    }

    @Test
    fun rejectsUrlsOutsideTheOriginOfTheProfile() {
        val server = MockWebServer()
        server.start()
        try {
            val transport = transport(server)

            assertThrows(HistoryMediaException::class.java) {
                transport.checkUrl("https://evil.example.com/vod/x/master.m3u8")
            }
            assertThrows(HistoryMediaException::class.java) {
                transport.checkUrl("http://localhost:${server.port}@evil.example.com/vod/x/master.m3u8")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsSchemesThatTheAppCannotServe() {
        val server = MockWebServer()
        server.start()
        try {
            val transport = transport(server)

            assertThrows(HistoryMediaException::class.java) { transport.checkUrl("file:///etc/passwd") }
            assertThrows(HistoryMediaException::class.java) { transport.checkUrl("content://media/external/file/1") }
            assertThrows(HistoryMediaException::class.java) { transport.checkUrl("asset:///vod/x/master.m3u8") }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun theSessionCookieAlwaysWinsOverASuppliedHeader() {
        val server = MockWebServer()
        server.start()
        try {
            val transport = transport(server)

            val headers = transport.headers(mapOf("Cookie" to "attacker=1", "X-Test" to "keep"))

            assertEquals("session=secret", headers["Cookie"])
            assertEquals("keep", headers["X-Test"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun everyRequestOfTheOriginCarriesTheSessionCookie() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""#EXTM3U"""))
            server.enqueue(MockResponse().setBody("""#EXTM3U"""))
            val transport = transport(server)

            transport.client.newCall(transport.request(server.url("/frigate/vod/x/master.m3u8").toString())).execute()
                .use { }
            transport.client.newCall(transport.request(server.url("/frigate/vod/x/segment0.ts").toString())).execute()
                .use { }

            val master = server.takeRequest()
            val segment = server.takeRequest()
            assertEquals("session=secret", master.getHeader("Cookie"))
            assertEquals("session=secret", segment.getHeader("Cookie"))
            assertEquals("/frigate/vod/x/master.m3u8", master.path)
            assertEquals("/frigate/vod/x/segment0.ts", segment.path)
        }
    }

    @Test
    fun aForeignOriginIsRejectedBeforeAnyRequestReachesIt() {
        val own = MockWebServer()
        val foreign = MockWebServer()
        own.start()
        foreign.start()
        try {
            val transport = transport(own)

            assertThrows(HistoryMediaException::class.java) {
                transport.request(foreign.url("/vod/x/master.m3u8").toString())
            }

            assertEquals(0, foreign.requestCount)
            assertEquals(0, own.requestCount)
        } finally {
            own.shutdown()
            foreign.shutdown()
        }
    }

    @Test
    fun redirectsAreNeverFollowed() {
        val server = MockWebServer()
        val target = MockWebServer()
        server.start()
        target.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/vod/x/master.m3u8").toString()))
            val transport = transport(server)

            val response = transport.client
                .newCall(transport.request(server.url("/frigate/vod/x/master.m3u8").toString()))
                .execute()

            assertEquals(302, response.code)
            response.close()
            assertEquals(0, target.requestCount)
        } finally {
            server.shutdown()
            target.shutdown()
        }
    }

    @Test
    fun aManifestMayOnlyPullMediaFromTheOwnOrigin() {
        val own = MockWebServer()
        val foreign = MockWebServer()
        own.start()
        foreign.start()
        try {
            val transport = transport(own)
            val masterUrl = own.url("/frigate/vod/front_door/master.m3u8").toString()
            own.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=1000000
                    child.m3u8
                    #EXT-X-MEDIA:TYPE=AUDIO,URI="https://cdn.example.com/audio.m3u8"
                    """.trimIndent(),
                ),
            )

            val master = transport.client.newCall(transport.request(masterUrl)).execute().use { it.body!!.string() }
            val child = master.lineSequence().first { it.endsWith(".m3u8") && !it.startsWith("#") }
            val foreignUri = master.lineSequence().first { it.contains("cdn.example.com") }.substringAfter("\"")
            val resolvedChild = URI(masterUrl).resolve(child)

            assertEquals(own.url("/frigate/vod/front_door/child.m3u8").toString(), resolvedChild.toString())
            transport.checkUrl(resolvedChild.toString())
            assertThrows(HistoryMediaException::class.java) { transport.checkUrl(foreignUri) }

            assertEquals(1, own.requestCount)
            assertEquals(0, foreign.requestCount)
        } finally {
            own.shutdown()
            foreign.shutdown()
        }
    }

    @Test
    fun theFactoryBuildsGuardedDataSources() {
        val server = MockWebServer()
        server.start()
        try {
            val profile = ServerProfile("Test", server.url("/frigate").toString())

            val factory = HistoryHlsDataSourceFactory.create(profile, "session=secret")

            assertTrue(factory.createDataSource() is HistoryMediaDataSource)
        } finally {
            server.shutdown()
        }
    }
}
