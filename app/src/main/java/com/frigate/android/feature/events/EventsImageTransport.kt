package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class EventsImageTransport(
    private val profile: ServerProfile,
    private val cookie: String,
    private val maxBytes: Int = 2 * 1024 * 1024,
) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun load(path: String, height: Int? = null): ByteArray {
        require(height == null || height in 1..1280)
        val location = eventsMediaUrl(profile, path) ?: throw IOException("Invalid image location")
        val url = location + if (height == null) "" else "?h=$height"
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Cookie", cookie).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw FrigateHttpException(it.code, "Image request failed")
                            val body = it.body ?: throw IOException("Missing image")
                            if (body.contentLength() > maxBytes) throw IOException("Image is too large")
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > maxBytes) throw IOException("Image is too large")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
