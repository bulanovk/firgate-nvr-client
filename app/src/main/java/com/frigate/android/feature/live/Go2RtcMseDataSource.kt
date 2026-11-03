package com.frigate.android.feature.live

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class Go2RtcMseDataSourceFactory(
    private val webSocketUrl: String,
    private val cookie: String,
    private val codecOffer: String,
    private val client: OkHttpClient,
    private val onMimeType: (String) -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): Go2RtcMseDataSource =
        Go2RtcMseDataSource(webSocketUrl, cookie, codecOffer, client, onMimeType)
}

class Go2RtcMseDataSource(
    private val webSocketUrl: String,
    private val cookie: String,
    private val codecOffer: String,
    private val client: OkHttpClient,
    private val onMimeType: (String) -> Unit,
) : DataSource {
    private val queue = MseByteQueue()
    private val negotiation = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentUri: Uri? = null
    @Volatile private var negotiatedMimeType: String? = null
    @Volatile private var failure: IOException? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        check(webSocket == null) { "DataSource instances can only be opened once" }
        currentUri = dataSpec.uri
        return openConnection()
    }

    internal fun openConnection(): Long {
        check(webSocket == null) { "DataSource instances can only be opened once" }
        val request = Request.Builder()
            .url(webSocketUrl)
            .header("Cookie", cookie)
            .build()
        webSocket = client.newWebSocket(request, listener)

        try {
            if (!negotiation.await(NEGOTIATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting for go2rtc MSE negotiation")
            }
            failure?.let { throw it }
            if (negotiatedMimeType == null) throw IOException("go2rtc did not negotiate an MSE video stream")
            return C.LENGTH_UNSET.toLong()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while negotiating go2rtc MSE stream", interrupted)
        } catch (exception: IOException) {
            close()
            throw exception
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        queue.read(buffer, offset, length)

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            webSocket?.close(NORMAL_CLOSE_CODE, "Media3 source closed")
            queue.close(IOException("go2rtc MSE source was closed"))
            negotiation.countDown()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val envelope = JsonObject().apply {
                addProperty("type", "mse")
                add("value", JsonPrimitive(codecOffer))
            }
            if (!webSocket.send(envelope.toString())) {
                fail(IOException("Unable to send go2rtc MSE codec offer"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = Go2RtcMseProtocol.parseControlMessage(text)) {
                is MseControlMessage.Negotiated -> {
                    if (!Go2RtcMseProtocol.isMimeTypeSupported(message.mimeType, codecOffer)) {
                        fail(IOException("go2rtc negotiated an unsupported video codec"))
                        return
                    }
                    negotiatedMimeType = message.mimeType
                    try {
                        onMimeType(message.mimeType)
                    } catch (exception: RuntimeException) {
                        fail(IOException("Unable to accept go2rtc MSE MIME type", exception))
                        return
                    }
                    negotiation.countDown()
                }
                is MseControlMessage.ServerError ->
                    fail(IOException("go2rtc MSE error: ${message.message}"))
                MseControlMessage.UnexpectedMessage -> {
                    if (negotiatedMimeType == null) {
                        fail(IOException("Unexpected go2rtc MSE negotiation response"))
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!queue.offer(bytes.toByteArray())) {
                fail(IOException("go2rtc MSE buffer exceeded its 2 MiB limit"))
                webSocket.cancel()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(IOException("go2rtc MSE WebSocket failed", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) fail(IOException("go2rtc MSE WebSocket is closing: $code"))
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) fail(IOException("go2rtc MSE WebSocket closed: $code"))
        }
    }

    private fun fail(exception: IOException) {
        if (failure == null) failure = exception
        queue.close(failure)
        negotiation.countDown()
    }

    private companion object {
        const val NEGOTIATION_TIMEOUT_SECONDS = 10L
        const val NORMAL_CLOSE_CODE = 1000
    }
}
