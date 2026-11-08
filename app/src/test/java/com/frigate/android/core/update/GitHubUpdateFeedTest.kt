package com.frigate.android.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubUpdateFeedTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun feed() = GitHubUpdateFeed(
        releasesUrl = server.url("/repos/o/r/releases/latest").toString(),
        devUrl = server.url("/pages/version.json").toString(),
    )

    @Test
    fun latestReleaseTakesTheApkAsset() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"tag_name":"v0.0.3","body":"Notes","assets":[
              {"name":"checksums.txt","browser_download_url":"https://dl/checksums.txt"},
              {"name":"frigate-android-0.0.3.apk","browser_download_url":"https://dl/frigate-android-0.0.3.apk"}
            ]}
        """.trimIndent()))

        val update = feed().latest(UpdateChannel.Release)

        assertEquals(AvailableUpdate("0.0.3", "https://dl/frigate-android-0.0.3.apk", "Notes", null), update)
        val request = server.takeRequest()
        assertEquals("/repos/o/r/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun releaseWithoutAnApkIsNoUpdate() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.0.3","assets":[]}"""))
        assertNull(feed().latest(UpdateChannel.Release))
    }

    @Test
    fun devBuildResolvesTheApkNextToTheManifest() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"versionName":"0.1.0-test.14+abc1234","build":14,"commit":"abc1234","apk":"frigate-android-test.apk"}""",
        ))

        val update = feed().latest(UpdateChannel.Dev)

        assertEquals(
            AvailableUpdate("0.1.0-test.14+abc1234", server.url("/pages/frigate-android-test.apk").toString(), null, 14),
            update,
        )
    }

    @Test
    fun localBuildAsksNothing() = runBlocking {
        assertNull(feed().latest(UpdateChannel.None))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpErrorFails() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows<java.io.IOException> { runBlocking { feed().latest(UpdateChannel.Release) } }
    }
}
