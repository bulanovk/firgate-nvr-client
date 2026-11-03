package com.frigate.android.feature.live

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Go2RtcMseDataSourceTest {
    @Test
    fun sendsVideoOnlyOfferWithSessionCookie() {
        val server = MockWebServer()
        val offerMessage = AtomicReference<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                offerMessage.set(text)
                negotiate(webSocket)
            }
        }))
        server.start()
        try {
            val source = dataSource(server, "avc1.640029")
            source.openConnection()
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("session=secret", request?.getHeader("Cookie"))
            assertTrue(offerMessage.get().contains("avc1.640029"))
            assertTrue(!offerMessage.get().contains("mp4a") && !offerMessage.get().contains("opus"))
            source.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun waitsForMimeBeforeReturningSourceBytes() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                negotiate(webSocket)
                webSocket.send(ByteString.of(1, 2, 3))
            }
        }))
        server.start()
        try {
            val mime = AtomicReference<String>()
            val source = dataSource(server, "avc1.640029", mime)
            source.openConnection()
            assertEquals("video/mp4; codecs=\"avc1.640029\"", mime.get())
            val bytes = ByteArray(3)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            source.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun surfacesServerCodecError() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"error","value":"mse: codecs not matched"}""")
            }
        }))
        server.start()
        try {
            val source = dataSource(server, "avc1.640029")
            assertThrows(IOException::class.java) {
                source.openConnection()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun preservesBinaryFrameOrder() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                negotiate(webSocket)
                webSocket.send(ByteString.of(1, 2))
                webSocket.send(ByteString.of(3, 4))
            }
        }))
        server.start()
        try {
            val source = dataSource(server, "avc1.640029")
            source.openConnection()
            val bytes = ByteArray(4)
            var bytesRead = 0
            while (bytesRead < bytes.size) {
                val read = source.read(bytes, bytesRead, bytes.size - bytesRead)
                if (read < 0) break
                bytesRead += read
            }
            assertEquals(4, bytesRead)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
            source.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun socketCloseUnblocksDataSourceRead() {
        val server = MockWebServer()
        val closeSocket = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                negotiate(webSocket)
                closeSocket.await(2, TimeUnit.SECONDS)
                webSocket.close(1000, "done")
            }
        }))
        server.start()
        try {
            val source = dataSource(server, "avc1.640029")
            source.openConnection()
            closeSocket.countDown()
            assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            source.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun localCloseCancelsBlockedDataSourceRead() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                negotiate(webSocket)
            }
        }))
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = dataSource(server, "avc1.640029")
            source.openConnection()
            val read = executor.submit<Int> { source.read(ByteArray(1), 0, 1) }
            source.close()
            val failure = assertThrows(ExecutionException::class.java) {
                read.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IOException)
        } finally {
            executor.shutdownNow()
            server.shutdown()
        }
    }

    @Test
    fun closeCancelsNegotiationWait() {
        val server = MockWebServer()
        val offerReceived = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : TestWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                offerReceived.countDown()
            }
        }))
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = dataSource(server, "avc1.640029")
            val opening = executor.submit<Long> { source.openConnection() }
            assertTrue(offerReceived.await(2, TimeUnit.SECONDS))
            source.close()
            val failure = assertThrows(ExecutionException::class.java) {
                opening.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IOException)
        } finally {
            executor.shutdownNow()
            server.shutdown()
        }
    }

    private open class TestWebSocketListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private fun dataSource(
        server: MockWebServer,
        offer: String,
        mime: AtomicReference<String> = AtomicReference(),
    ) = Go2RtcMseDataSourceFactory(
        server.url("/api/ws").toString(), "session=secret", offer,
        OkHttpClient(), onMimeType = { mime.set(it) },
    ).createDataSource()

    private fun negotiate(webSocket: WebSocket) {
        webSocket.send("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""")
    }
}
